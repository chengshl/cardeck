package io.github.shaonialife.cardeck.input

/** An AirPlay /command HID payload may put its fields in the command root
 * or in the conventional `params` map. Support both without guessing
 * arbitrary data field formats or logging input payloads. */
internal object AirPlayHidCommand {
    data class Input(val uuid: String?, val report: ByteArray?, val layout: String)

    fun parse(command: Map<*, *>): Input {
        val params = command["params"] as? Map<*, *>
        val rootReport = command["hidReport"] as? ByteArray
        val nestedReport = params?.get("hidReport") as? ByteArray
        val report = rootReport ?: nestedReport
        val uuid = (command["uuid"] ?: params?.get("uuid"))?.toString()
        return Input(
            uuid = uuid,
            report = report,
            layout = if (rootReport != null) "root" else if (nestedReport != null) "params" else "missing",
        )
    }
}
