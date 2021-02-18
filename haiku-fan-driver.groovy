metadata {
    definition(name: "Haiku Fan Component", namespace: "community", author: "Dinesh Pannu", component: true) {
        capability "Switch"
        capability "SwitchLevel"
        capability "FanControl"
        capability "Refresh"
        
        command "cycleSpeed"
        command "reverseFan"

        attribute "fanDirection", "string"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void updated() {
    log.info "Updated..."
    log.warn "description logging is: ${txtEnable == true}"
}

void installed() {
    log.info "Installed..."
    device.updateSetting("txtEnable",[type:"bool",value:true])
    refresh()
}

void parse(String description) { log.warn "parse(String description) not implemented" }

void parse(List<Map> description) {
    description.each {
        if (it.name in ["switch", "level", "speed", "fanDirection"]) {
            if (txtEnable) log.info it.descriptionText
            sendEvent(it)
        }
    }
}

void on() {
    parent?.componentOn(this.device)
}

void off() {
    parent?.componentOff(this.device)
}

void setLevel(level) {
    parent?.componentSetLevel(this.device,level)
}

void setLevel(level, ramp) {
    parent?.componentSetLevel(this.device,level,ramp)
}

void setSpeed(speed) {
    parent?.componentSetSpeed(this.device, speed)
}

void cycleSpeed() {
    parent?.componentCycleSpeed(this.device)
}

void reverseFan() {
    parent?.componentReverseFan(this.device)
}

void refresh() {
    parent?.componentRefresh(this.device)
}