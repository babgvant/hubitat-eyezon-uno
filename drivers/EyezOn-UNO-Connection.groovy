/**
 *  EyezOn UNO Connection Driver
 *
 *  Talks directly to the EyezOn UNO's TPI socket (port 4025) using the DSC EnvisaLink
 *  TPI protocol (checksummed ASCII frames): "CCC" + data + "CKS" + CR/LF, where CKS is
 *  the sum of the ASCII byte values of every command/data character, truncated to 8
 *  bits and written as two uppercase hex characters. See EnvisaLinkTPI-1-08 for the
 *  authoritative command/event tables this driver implements.
 */
metadata {
    definition(name: "EyezOn UNO Connection", namespace: "eyezonUno", author: "babgvant") {
        capability "Initialize"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        attribute "connectionState", "string"
        attribute "lastAck", "string"
        attribute "lastError", "string"
        attribute "lastFrame", "string"
        attribute "batteryTrouble", "string"
        attribute "acTrouble", "string"
        attribute "bellTrouble", "string"
        attribute "ftcTrouble", "string"
        attribute "systemTamper", "string"

        command "armAway", [[name: "Partition", type: "NUMBER"]]
        command "armStay", [[name: "Partition", type: "NUMBER"]]
        command "disarm", [[name: "Partition", type: "NUMBER"], [name: "Code", type: "STRING"]]
        command "poll"
        command "statusReport"
        command "reconnect"
    }

    preferences {
        input name: "ip", type: "text", title: "UNO IP Address", required: true
        input name: "port", type: "number", title: "UNO Port", defaultValue: 4025, required: true
        input name: "password", type: "password", title: "UNO TPI Password", required: true
        input name: "masterCode", type: "password", title: "Master code (for auto code-request replies)", required: false
        input name: "heartbeatMinutes", type: "enum", title: "Poll interval",
            options: ["10s": "Every 10 seconds", "20s": "Every 20 seconds", "30s": "Every 30 seconds", "1": "Every 1 minute", "3": "Every 3 minutes", "5": "Every 5 minutes"],
            defaultValue: "1", required: true
        input name: "watchdogMinutes", type: "enum", title: "Reconnect if no frames seen for",
            options: ["3": "3 minutes", "5": "5 minutes", "10": "10 minutes"],
            defaultValue: "5", required: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "traceLogging", type: "bool", title: "Enable trace (raw frame) logging", defaultValue: false
    }
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    initialize()
}

def initialize() {
    state.rxBuffer = ""
    state.commandQueue = []
    state.commandInFlight = null
    state.loggedIn = false
    state.lastFrameTs = 0L
    state.lastFrameEventTs = 0L

    sendEvent(name: "connectionState", value: "disconnected")

    scheduleHealthChecks()
    reconnect()
}

private void scheduleHealthChecks() {
    String hb = (settings.heartbeatMinutes ?: "1").toString()
    if (hb in ["10s", "20s", "30s"]) {
        Integer seconds = hb.replace("s", "").toInteger()
        schedule("0/${seconds} * * * * ?", "heartbeatCheck")
    } else if (hb == "1") {
        runEvery1Minute("heartbeatCheck")
    } else if (hb == "3") {
        runEvery3Minutes("heartbeatCheck")
    } else {
        runEvery5Minutes("heartbeatCheck")
    }
    runEvery5Minutes("watchdogCheck")
}

def refresh() {
    statusReport()
}

def reconnect() {
    try {
        interfaces.rawSocket.close()
    } catch (ignored) {
    }

    state.loggedIn = false
    state.commandInFlight = null
    state.commandQueue = []

    Integer p = safeInt(settings.port, 4025)
    logInfo("Connecting to ${settings.ip}:${p}")
    sendEvent(name: "connectionState", value: "connecting")

    try {
        interfaces.rawSocket.connect(settings.ip, p, byteInterface: false)
    } catch (Exception e) {
        log.error "EyezOn UNO: socket connect failed: ${e}"
        sendEvent(name: "connectionState", value: "error")
        runIn(15, "reconnect")
    }
}

def socketStatus(String message) {
    logDebug("socketStatus: ${message}")
    String m = (message ?: "").toLowerCase()

    if (m.contains("error") || m.contains("close") || m.contains("disconnect") || m.contains("failure")) {
        logWarn("Socket disconnected: ${message}")
        sendEvent(name: "connectionState", value: "disconnected")
        state.loggedIn = false
        state.commandInFlight = null
        runIn(10, "reconnect")
    }
}

def heartbeatCheck() {
    if (device.currentValue("connectionState") == "authenticated") {
        poll()
    }
}

def watchdogCheck() {
    long lastTs = (state.lastFrameTs ?: 0L) as Long
    if (lastTs == 0L) return

    Integer wdMinutes = safeInt(settings.watchdogMinutes, 5)
    long ageSeconds = (now() - lastTs) / 1000L
    if (ageSeconds > (wdMinutes * 60)) {
        logWarn("Watchdog reconnect: no frames for ${ageSeconds}s")
        reconnect()
    }
}

/* ---------------- Outbound frame construction ---------------- */

private String calcChecksum(String cmdAndData) {
    int sum = 0
    cmdAndData.each { String ch -> sum += (int) ch.charAt(0) }
    sum = sum & 0xFF
    String hex = Integer.toHexString(sum).toUpperCase()
    return hex.length() < 2 ? ("0" + hex) : hex
}

private void rawSend(String cmd, String data = "") {
    String body = cmd + (data ?: "")
    String frame = body + calcChecksum(body)
    if (settings.traceLogging) {
        log.debug "EyezOn UNO TX >>> ${frame}"
    }
    interfaces.rawSocket.sendMessage(frame + "\r\n")
}

def poll() {
    enqueueCommand("000", "", "poll")
}

def statusReport() {
    enqueueCommand("001", "", "statusReport")
}

def armAway(partition = 1) {
    enqueueCommand("030", "${safeInt(partition, 1)}", "armAway")
}

def armStay(partition = 1) {
    enqueueCommand("031", "${safeInt(partition, 1)}", "armStay")
}

def disarm(partition = 1, String code = null) {
    String useCode = code ?: settings.masterCode
    if (!useCode) {
        log.warn "EyezOn UNO: disarm requested without a code"
        return
    }
    enqueueCommand("040", "${safeInt(partition, 1)}${useCode}", "disarm")
}

/* ---------------- Command queue ---------------- */

private void enqueueCommand(String cmd, String data, String name) {
    state.commandQueue = state.commandQueue ?: []
    state.commandQueue << [cmd: cmd, data: (data ?: ""), name: name]
    processQueue()
}

private void processQueue() {
    if (!state.loggedIn) return
    if (state.commandInFlight) return
    if (!(state.commandQueue instanceof List) || state.commandQueue.isEmpty()) return

    def next = state.commandQueue.remove(0)
    state.commandInFlight = [cmd: next.cmd, data: next.data, name: next.name, sentAt: now(), retries: 0]
    logDebug("Sending ${next.name}")
    rawSend(next.cmd as String, next.data as String)
    runIn(4, "commandTimeoutCheck")
}

def commandTimeoutCheck() {
    def inflight = state.commandInFlight
    if (!inflight) return

    long ageMs = now() - (inflight.sentAt as Long)
    if (ageMs < 3500L) return

    if ((inflight.retries as Integer) < 1) {
        inflight.retries = (inflight.retries as Integer) + 1
        inflight.sentAt = now()
        state.commandInFlight = inflight
        logWarn("Retrying ${inflight.name}")
        rawSend(inflight.cmd as String, inflight.data as String)
        runIn(4, "commandTimeoutCheck")
    } else {
        logWarn("Command timed out: ${inflight.name}")
        sendEvent(name: "lastAck", value: "timeout:${inflight.name}")
        state.commandInFlight = null
        processQueue()
    }
}

/* ---------------- Inbound parsing ---------------- */

def parse(String message) {
    if (message == null) return

    String decoded = decodeIncoming(message)
    if (decoded == null || decoded.isEmpty()) return

    state.lastFrameTs = now()
    state.rxBuffer = (state.rxBuffer ?: "") + decoded

    while (true) {
        String buffer = state.rxBuffer ?: ""
        int idx = buffer.indexOf('\n')
        if (idx < 0) break

        String frame = buffer.substring(0, idx).replace("\r", "").trim()
        state.rxBuffer = buffer.substring(idx + 1)

        if (frame) parseFrame(frame)
    }
}

private String decodeIncoming(String message) {
    try {
        byte[] data = hubitat.helper.HexUtils.hexStringToByteArray(message)
        return new String(data, "US-ASCII")
    } catch (ignored) {
        return message
    }
}

private void parseFrame(String frame) {
    if (settings.traceLogging) {
        log.debug "EyezOn UNO RX <<< ${frame}"
    }
    // lastFrame is diagnostic only. Publishing every frame floods device events
    // and makes Current States redraw continuously on a busy TPI connection.
    long frameEventTs = (state.lastFrameEventTs ?: 0L) as Long
    if (now() - frameEventTs >= 60000L) {
        String displayedFrame = frame.take(1024)
        if (device.currentValue("lastFrame") != displayedFrame) {
            sendEvent(name: "lastFrame", value: displayedFrame)
        }
        state.lastFrameEventTs = now()
    }

    if (frame.length() < 5) {
        logDebug("Ignoring short frame: ${frame}")
        return
    }

    String cmd = frame.substring(0, 3)
    String recvCksum = frame.substring(frame.length() - 2)
    String data = frame.substring(3, frame.length() - 2)

    String expectCksum = calcChecksum(cmd + data)
    if (expectCksum != recvCksum.toUpperCase()) {
        logDebug("Checksum mismatch on frame ${frame} (expected ${expectCksum})")
    }

    dispatch(cmd, data)
}

private void dispatch(String cmd, String data) {
    switch (cmd) {
        case "500": handleAck(data); break
        case "501": handleCommandError(); break
        case "502": handleSystemError(data); break
        case "505": handleLogin(data); break

        case "601": zoneEvent(data, 1, true); break
        case "602": zoneEvent(data, 1, false); break
        case "605": zoneEvent(data, 0, true); break
        case "606": zoneEvent(data, 0, false); break
        case "609": zoneEvent(data, 0, true); break
        case "610": zoneEvent(data, 0, false); break

        case "650": partitionEvent(data, "ready"); break
        case "651": partitionEvent(data, "notReady"); break
        case "652": partitionArmed(data); break
        case "653": partitionEvent(data, "ready"); break
        case "654": partitionEvent(data, "alarm"); break
        case "655": partitionEvent(data, "disarmed"); break
        case "656": partitionEvent(data, "exitDelay"); break
        case "657": partitionEvent(data, "entryDelay"); break
        case "658": partitionEvent(data, "lockout"); break
        case "659": partitionEvent(data, "failedToArm"); break
        case "663": partitionEvent(data, "chimeEnabled"); break
        case "664": partitionEvent(data, "chimeDisabled"); break
        case "670": partitionEvent(data, "invalidCode"); break

        case "840": partitionTrouble(data, true); break
        case "841": partitionTrouble(data, false); break

        case "800": sendEvent(name: "batteryTrouble", value: "active"); break
        case "801": sendEvent(name: "batteryTrouble", value: "clear"); break
        case "802": sendEvent(name: "acTrouble", value: "active"); break
        case "803": sendEvent(name: "acTrouble", value: "clear"); break
        case "806": sendEvent(name: "bellTrouble", value: "active"); break
        case "807": sendEvent(name: "bellTrouble", value: "clear"); break
        case "814": sendEvent(name: "ftcTrouble", value: "active"); break
        case "815": sendEvent(name: "ftcTrouble", value: "clear"); break
        case "829": sendEvent(name: "systemTamper", value: "active"); break
        case "830": sendEvent(name: "systemTamper", value: "clear"); break

        case "900":
        case "921":
        case "922":
            handleCodeRequired()
            break

        default:
            logDebug("Unhandled TPI command ${cmd}: ${data}")
            break
    }
}

private void handleAck(String data) {
    String ackedCmd = data?.length() >= 3 ? data.substring(0, 3) : data
    sendEvent(name: "lastAck", value: "ok:${ackedCmd}")
    if (state.commandInFlight) {
        state.commandInFlight = null
    }
    processQueue()
}

private void handleCommandError() {
    logWarn("Command rejected: bad checksum")
    sendEvent(name: "lastAck", value: "checksumError")
    state.commandInFlight = null
    processQueue()
}

private void handleSystemError(String data) {
    sendEvent(name: "lastError", value: data)
    logWarn("System error ${data}")
    state.commandInFlight = null
    processQueue()
}

private void handleLogin(String data) {
    switch (data) {
        case "3":
            logInfo("UNO requested login")
            rawSend("005", settings.password ?: "")
            break
        case "1":
            logInfo("UNO login successful")
            state.loggedIn = true
            sendEvent(name: "connectionState", value: "authenticated")
            statusReport()
            break
        case "0":
            logWarn("UNO login failed - check password")
            sendEvent(name: "connectionState", value: "auth_failed")
            break
        case "2":
            logWarn("UNO login timed out")
            runIn(5, "reconnect")
            break
        default:
            logDebug("Unrecognized login status: ${data}")
            break
    }
}

private void handleCodeRequired() {
    if (settings.masterCode) {
        rawSend("200", settings.masterCode)
    } else {
        logWarn("UNO requested an access code but no master code is configured")
    }
}

private void zoneEvent(String data, int zoneOffset, boolean active) {
    if (data == null || data.length() < zoneOffset + 3) return
    Integer zone = safeInt(data.substring(zoneOffset, zoneOffset + 3), null)
    if (zone == null || zone <= 0) return
    parent?.zoneStateChanged(zone, active)
}

private void partitionEvent(String data, String status) {
    if (data == null || data.isEmpty()) return
    Integer partition = safeInt(data.substring(0, 1), 1)
    parent?.partitionStateChanged(partition, status)
}

private void partitionArmed(String data) {
    if (data == null || data.length() < 2) return
    Integer partition = safeInt(data.substring(0, 1), 1)
    String mode = data.substring(1, 2)
    String status = (mode == "1" || mode == "3") ? "armedStay" : "armedAway"
    parent?.partitionStateChanged(partition, status)
}

private void partitionTrouble(String data, boolean active) {
    if (data == null || data.isEmpty()) return
    Integer partition = safeInt(data.substring(0, 1), 1)
    parent?.partitionTroubleChanged(partition, active)
}

/* ---------------- Helpers ---------------- */

private Integer safeInt(def val, Integer fallback) {
    try {
        if (val == null) return fallback
        return Integer.parseInt(val.toString().trim())
    } catch (ignored) {
        return fallback
    }
}

private void logInfo(String msg) { log.info "EyezOn UNO: ${msg}" }
private void logWarn(String msg) { log.warn "EyezOn UNO: ${msg}" }
private void logDebug(String msg) { if (settings.debugLogging) log.debug "EyezOn UNO: ${msg}" }
