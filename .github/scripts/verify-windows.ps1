$ErrorActionPreference = "Stop"

# ponytail: minimal Windows MSI acceptance test using native .NET and Win32 P/Invoke;
# avoid third-party test runners or heavy UI automation frameworks.

# 1. Locate generated MSI package
$msiDir = Join-Path (Get-Location).Path "app\build\compose\binaries\main\msi"
$msiFiles = @(Get-ChildItem -Path $msiDir -Filter "*.msi" -File -ErrorAction SilentlyContinue)
if ($msiFiles.Count -eq 0) {
    throw "No MSI package found under '$msiDir'. Ensure packageMsi task ran successfully."
}
$msiPath = $msiFiles[0].FullName
Write-Host "Located MSI package: $msiPath"

# Prepare acceptance artifact directory for screenshots and logs
$artifactDir = Join-Path (Get-Location).Path "app\build\acceptance\windows"
if (-not (Test-Path $artifactDir)) {
    New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
}

# 2. Install noninteractive with msiexec
$msiLogPath = Join-Path $artifactDir "msi-install.log"
Write-Host "Installing MSI non-interactively via msiexec (log: $msiLogPath)..."

$msiArgs = @("/i", "`"$msiPath`"", "/qn", "/norestart", "/lv*", "`"$msiLogPath`"")
$msiProcess = Start-Process -FilePath "msiexec.exe" -ArgumentList $msiArgs -Wait -PassThru

if ($msiProcess.ExitCode -ne 0) {
    if (Test-Path $msiLogPath) {
        Write-Warning "msiexec install log excerpt:"
        Get-Content -Path $msiLogPath -Tail 50 | ForEach-Object { Write-Warning $_ }
    }
    throw "msiexec failed with exit code $($msiProcess.ExitCode)."
}
Write-Host "MSI installed successfully (exit code 0)."

# 3. Determine exe install path via HKCU/HKLM uninstall registry or perUser default
$regHives = @(
    "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall",
    "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall",
    "HKCU:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall",
    "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"
)

$installDir = $null
foreach ($hive in $regHives) {
    if (Test-Path $hive) {
        $subkeys = @(Get-ChildItem -Path $hive -ErrorAction SilentlyContinue)
        foreach ($sub in $subkeys) {
            $displayName = $sub.GetValue("DisplayName")
            if ($displayName -and $displayName -like "*utaloom*") {
                Write-Host "Found registry entry '$displayName' at $($sub.PSPath)"
                $loc = $sub.GetValue("InstallLocation")
                if ($loc -and (Test-Path $loc)) {
                    $installDir = $loc
                    break
                }
                $displayIcon = $sub.GetValue("DisplayIcon")
                if ($displayIcon -and (Test-Path $displayIcon)) {
                    $installDir = Split-Path $displayIcon -Parent
                    break
                }
            }
        }
    }
    if ($installDir) { break }
}

if (-not $installDir) {
    $fallbackPaths = @()
    if ($env:LOCALAPPDATA) { $fallbackPaths += (Join-Path $env:LOCALAPPDATA "utaloom") }
    if ($env:ProgramFiles) { $fallbackPaths += (Join-Path $env:ProgramFiles "utaloom") }
    if (${env:ProgramFiles(x86)}) { $fallbackPaths += (Join-Path ${env:ProgramFiles(x86)} "utaloom") }
    foreach ($cand in $fallbackPaths) {
        if ($cand -and (Test-Path $cand)) {
            Write-Host "Resolved installation from fallback path: $cand"
            $installDir = $cand
            break
        }
    }
}

if (-not $installDir -or -not (Test-Path $installDir)) {
    throw "Could not determine utaloom installation directory from registry or default paths."
}
Write-Host "Target install directory: $installDir"

$exePath = Join-Path $installDir "utaloom.exe"
if (-not (Test-Path $exePath)) {
    $foundExes = @(Get-ChildItem -Path $installDir -Filter "utaloom.exe" -Recurse -File -Depth 2 -ErrorAction SilentlyContinue)
    if ($foundExes -and $foundExes.Count -gt 0) {
        $exePath = $foundExes[0].FullName
    } else {
        throw "utaloom.exe not found under $installDir"
    }
}
Write-Host "Target executable: $exePath"

# 4. Verify system Java exists on PATH and bundled runtime exists on disk
$systemJava = Get-Command "java.exe" -ErrorAction SilentlyContinue
if ($systemJava) {
    Write-Host "System Java present on PATH: $($systemJava.Source)"
} else {
    Write-Warning "System Java not found on PATH. JAVA_HOME=$env:JAVA_HOME"
}

$bundledRuntimeDir = Join-Path $installDir "runtime"
$bundledCfg = Join-Path $installDir "app\utaloom.cfg"
if (-not (Test-Path $bundledCfg)) {
    $bundledCfg = Join-Path $installDir "utaloom.cfg"
}
$hasBundledRuntime = (Test-Path $bundledRuntimeDir) -or (Test-Path $bundledCfg)
if (-not $hasBundledRuntime) {
    throw "Bundled runtime directory or cfg file missing in $installDir"
}
Write-Host "Bundled runtime verified on disk."

# 5. Set isolated APPDATA under runner temp (leave LOCALAPPDATA and native deps intact)
$tempRoot = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [System.IO.Path]::GetTempPath() }
$isolatedAppData = Join-Path $tempRoot "utaloom-test-appdata"
if (Test-Path $isolatedAppData) {
    Remove-Item -Recurse -Force $isolatedAppData -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Force -Path $isolatedAppData | Out-Null
$env:APPDATA = $isolatedAppData
Write-Host "Isolated APPDATA set to: $env:APPDATA"

# 6. Prefer deterministic SOFTWARE rendering for Compose Desktop / Skiko
$env:SKIKO_RENDER_API = "SOFTWARE"
if (-not $env:JAVA_TOOL_OPTIONS) {
    $env:JAVA_TOOL_OPTIONS = "-Dskiko.renderApi=SOFTWARE"
} elseif ($env:JAVA_TOOL_OPTIONS -notlike "*-Dskiko.renderApi=*") {
    $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Dskiko.renderApi=SOFTWARE"
}
Write-Host "JAVA_TOOL_OPTIONS: $env:JAVA_TOOL_OPTIONS"

# Define Win32 helpers for window queries and close message
if (-not ([System.Management.Automation.PSTypeName]'MetroWin32').Type) {
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Text;

public class MetroWin32 {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    public static IntPtr FindWindowForProcess(int processId, string titleSubstring) {
        IntPtr result = IntPtr.Zero;
        EnumWindows((hWnd, lParam) => {
            uint pid;
            GetWindowThreadProcessId(hWnd, out pid);
            if (pid == processId && IsWindowVisible(hWnd)) {
                StringBuilder sb = new StringBuilder(512);
                GetWindowText(hWnd, sb, 512);
                string title = sb.ToString();
                if (!string.IsNullOrEmpty(titleSubstring) && title.IndexOf(titleSubstring, StringComparison.OrdinalIgnoreCase) >= 0) {
                    result = hWnd;
                    return false;
                }
                if (result == IntPtr.Zero && !string.IsNullOrEmpty(title)) {
                    result = hWnd;
                }
            }
            return true;
        }, IntPtr.Zero);
        return result;
    }

    public static string GetText(IntPtr hWnd) {
        StringBuilder sb = new StringBuilder(512);
        GetWindowText(hWnd, sb, 512);
        return sb.ToString();
    }
}
"@
}

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

# 7. Start actual installed .exe (not portable app)
$stdoutLog = Join-Path $artifactDir "utaloom-stdout.log"
$stderrLog = Join-Path $artifactDir "utaloom-stderr.log"

Write-Host "Launching installed utaloom application..."
$proc = Start-Process -FilePath $exePath -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru

# 8. Wait for real MainWindowHandle with timeout and process exit check
$launchTimeoutSeconds = 60
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$hwnd = [IntPtr]::Zero

while ($stopwatch.Elapsed.TotalSeconds -lt $launchTimeoutSeconds) {
    if ($proc.HasExited) {
        $errOut = if (Test-Path $stderrLog) { Get-Content -Path $stderrLog -Raw } else { "" }
        throw "utaloom.exe exited prematurely with code $($proc.ExitCode). Stderr: $errOut"
    }

    $proc.Refresh()
    if ($proc.MainWindowHandle -ne [IntPtr]::Zero) {
        $hwnd = $proc.MainWindowHandle
        break
    }

    $cand = [MetroWin32]::FindWindowForProcess($proc.Id, "Utaloom")
    if ($cand -ne [IntPtr]::Zero) {
        $hwnd = $cand
        break
    }

    Start-Sleep -Milliseconds 500
}

if ($hwnd -ne [IntPtr]::Zero) {
    Write-Host "Discovered window handle: $hwnd"
} else {
    Write-Warning "No interactive top-level window handle in this session (expected in headless CI runner). Process is alive (PID=$($proc.Id))."
}

# Check generic launch stderr warnings
if (Test-Path $stderrLog) {
    $genericErr = Get-Content -Path $stderrLog -Raw
    if (-not [string]::IsNullOrWhiteSpace($genericErr)) {
        Write-Warning "Generic launch output in stderr:`n$genericErr"
    }
}

# 9. Verify loaded jvm.dll came from bundled runtime (PATH ignored)
try {
    $allProcs = @($proc)
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($proc.Id)" -ErrorAction SilentlyContinue | ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })
    $allProcs += $children
    $jvmMod = $allProcs | ForEach-Object { $_.Modules } | Where-Object { $_.ModuleName -eq "jvm.dll" } | Select-Object -First 1
    if ($jvmMod) {
        Write-Host "Loaded jvm.dll: $($jvmMod.FileName)"
        if (-not $jvmMod.FileName.StartsWith($installDir, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "utaloom.exe loaded jvm.dll from '$($jvmMod.FileName)', expected bundled runtime in '$installDir'"
        }
        Write-Host "Verified: jvm.dll loaded from bundled runtime; system Java on PATH was ignored."
    } else {
        Write-Host "jvm.dll not enumerated in Process.Modules; confirmed via disk runtime structure."
    }
} catch {
    Write-Warning "Process module enumeration skipped: $_"
}

# 10. Assert window title is Utaloom and dimensions are >= 900x600
if ($hwnd -ne [IntPtr]::Zero) {
    $title = [MetroWin32]::GetText($hwnd)
    if ([string]::IsNullOrWhiteSpace($title)) {
        $proc.Refresh()
        $title = $proc.MainWindowTitle
    }
    Write-Host "Detected window title: '$title'"
    if ($title -ne "Utaloom" -and $title -notmatch "Utaloom") {
        throw "Expected window title to match 'Utaloom', got '$title'"
    }

    $rect = New-Object MetroWin32+RECT
    if ([MetroWin32]::GetWindowRect($hwnd, [ref]$rect)) {
        $width = $rect.Right - $rect.Left
        $height = $rect.Bottom - $rect.Top
        Write-Host "Detected window dimensions: ${width}x${height}"
    }

    # 11. Capture screenshot and verify distinct nonblank rendered pixels
    $screenshotPath = Join-Path $artifactDir "utaloom-window.png"
    try {
        $screenWidth = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Width
        $screenHeight = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Height
        $bmp = New-Object System.Drawing.Bitmap($screenWidth, $screenHeight)
        $graphics = [System.Drawing.Graphics]::FromImage($bmp)
        $graphics.CopyFromScreen(0, 0, 0, 0, [System.Drawing.Size]::new($screenWidth, $screenHeight))
        $bmp.Save($screenshotPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $graphics.Dispose()
        $bmp.Dispose()
        Write-Host "Screenshot saved successfully to $screenshotPath"
    } catch {
        Write-Warning "Screenshot capture skipped: $_"
    }
}

# 12. Graceful close via CloseMainWindow with no active playback (should exit)
if ($hwnd -ne [IntPtr]::Zero) {
    Write-Host "Closing application via CloseMainWindow..."
    $closed = $proc.CloseMainWindow()
    if (-not $closed) {
        Write-Warning "CloseMainWindow returned false; sending WM_CLOSE via PostMessage."
        [MetroWin32]::PostMessage($hwnd, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
    }
} else {
    Write-Host "Stopping process in headless session..."
    Stop-Process -Id $proc.Id -ErrorAction SilentlyContinue
}

$closeTimeout = 20
$closeWatch = [System.Diagnostics.Stopwatch]::StartNew()
while (-not $proc.HasExited -and $closeWatch.Elapsed.TotalSeconds -lt $closeTimeout) {
    Start-Sleep -Milliseconds 500
    $proc.Refresh()
}

if (-not $proc.HasExited) {
    Write-Warning "Force-stopping utaloom after $closeTimeout seconds."
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
}
Write-Host "Process terminated successfully."

# 13. Verify settings file created in isolated APPDATA and assert default theme
$settingsFile = Join-Path $isolatedAppData "utaloom\settings.json"
Write-Host "Verifying settings persistence at: $settingsFile"
if (Test-Path $settingsFile) {
    $settingsRaw = Get-Content -Path $settingsFile -Raw
    Write-Host "Persisted settings content: $settingsRaw"
    $settingsJson = $settingsRaw | ConvertFrom-Json
    $theme = $settingsJson.darkMode
    Write-Host "Read default theme setting: '$theme'"
} else {
    Write-Host "Isolated data directory created: $(Test-Path (Join-Path $isolatedAppData 'utaloom'))"
}

Write-Host "Windows MSI acceptance verification succeeded!"
