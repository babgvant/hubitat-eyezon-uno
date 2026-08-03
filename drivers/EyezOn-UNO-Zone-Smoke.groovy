/**
 *  EyezOn UNO Zone Driver - Smoke
 *
 *  Child device for a zone configured as a smoke/heat detector. State is pushed in by
 *  the parent app via parse() whenever the connection driver reports a zone event.
 */
metadata {
    definition(name: "EyezOn UNO Zone Smoke", namespace: "eyezonUno", author: "babgvant") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "SmokeDetector"
    }
}

def installed() {
    sendEvent(name: "smoke", value: "clear")
}

def parse(List<Map> events) {
    events?.each { Map evt -> sendEvent(evt) }
}

def refresh() {
    parent?.refreshAll()
}
