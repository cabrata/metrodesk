package timber.log

/** Minimal stand-in for Android's Timber so the shared innertube sources compile unchanged on desktop. */
object Timber {
    @JvmStatic
    var debug: Boolean = System.getenv("METRODESK_DEBUG") != null

    fun d(message: String, vararg args: Any?) = log("D", message, args)
    fun i(message: String, vararg args: Any?) = log("I", message, args)
    fun w(message: String, vararg args: Any?) = log("W", message, args)
    fun e(message: String, vararg args: Any?) = log("E", message, args)
    fun w(t: Throwable?, message: String = "", vararg args: Any?) = log("W", "$message ${t?.message}", args)
    fun e(t: Throwable?, message: String = "", vararg args: Any?) = log("E", "$message ${t?.message}", args)
    fun tag(tag: String): Timber = this

    private fun log(level: String, message: String, args: Array<out Any?>) {
        if (!debug && level == "D") return
        System.err.println("[$level] " + if (args.isEmpty()) message else message.format(*args))
    }
}
