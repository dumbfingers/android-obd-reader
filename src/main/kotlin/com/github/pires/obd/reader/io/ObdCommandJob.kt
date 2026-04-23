package com.github.pires.obd.reader.io

import com.github.pires.obd.commands.ObdCommand

/**
 * This class represents a job that ObdGatewayService will have to execute and
 * maintain until the job is finished. It is, thereby, the application
 * representation of an ObdCommand instance plus a state that will be
 * interpreted and manipulated by ObdGatewayService.
 */
class ObdCommandJob(val command: ObdCommand) {

    var id: Long = 0
    var state: ObdCommandJobState = ObdCommandJobState.NEW

    /**
     * The state of the command.
     */
    enum class ObdCommandJobState {
        NEW,
        RUNNING,
        FINISHED,
        EXECUTION_ERROR,
        BROKEN_PIPE,
        QUEUE_ERROR,
        NOT_SUPPORTED
    }
}
