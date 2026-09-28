/**
 *  EyezOn UNO Zone Driver - Contact
 *  Version: 1.0.4
 *
 *  Child device for a zone configured as a door/window contact. State is pushed in by
 *  the parent app via parse() whenever the connection driver reports a zone event.
 */
metadata {
    definition(name: "EyezOn UNO Zone Contact", namespace: "eyezonUno", author: "babgvant") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "ContactSensor"
    }
}

def installed() {
    sendEvent(name: "contact", value: "closed")
}

def parse(List<Map> events) {
    events?.each { Map evt -> sendEvent(evt) }
}

def refresh() {
    parent?.refreshAll()
}
