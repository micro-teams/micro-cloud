/*
 *  Description: The one way a machine event gets written. record() commits in its own transaction
 *               (REQUIRES_NEW) so an event survives whatever its caller's transaction does next: the
 *               provisioner's transaction stays open for the minutes a provision takes and may roll
 *               back at the end, and the events leading up to that failure are exactly the ones worth
 *               keeping — and being committed at once, they are readable through the API while the
 *               action is still in flight. Every event is also logged through slf4j at its level, so
 *               the application log reads as it did before the event log existed.
 *
 *  Author(s):
 *      Zhifei Li    <andylizf@outlook.com>
 *
 */

package app.microteams.microcloud.machine.instance

import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class MachineEventRecorder(private val repository: MachineEventRepository) {
    private val log = LoggerFactory.getLogger(MachineEventRecorder::class.java)

    private companion object {
        const val MESSAGE_MAX = 512
        /** An init script's output can run long; keep its tail, which is where a failure shows. */
        const val DETAIL_MAX = 64 * 1024
        const val STACK_FRAMES = 8
    }

    /**
     * Append one event to [machine]'s log and log the same line. [cause], when given, is appended
     * to [detail] as its class, message and the top of its stack, and printed with the log line at
     * ERROR level. Safe from @Async code and from inside any transaction: the event commits on its
     * own before this returns.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        machine: Machine,
        action: MachineEventAction,
        phase: MachineEventPhase,
        message: String,
        level: MachineEventLevel = MachineEventLevel.INFO,
        detail: String? = null,
        cause: Throwable? = null,
    ) {
        val evidence =
            listOfNotNull(detail?.ifBlank { null }, cause?.let { describe(it) })
                .joinToString("\n")
                .ifEmpty { null }
        repository.save(
            MachineEvent(
                tenantId = machine.tenantId,
                machineId = machine.id,
                at = Instant.now(),
                action = action,
                phase = phase,
                level = level,
                message = message.take(MESSAGE_MAX),
                detail =
                    evidence?.let { if (it.length > DETAIL_MAX) it.takeLast(DETAIL_MAX) else it },
            )
        )
        val line = "machine ${machine.id} $action/$phase: $message"
        when (level) {
            MachineEventLevel.INFO -> log.info(line)
            MachineEventLevel.WARN -> log.warn(line)
            MachineEventLevel.ERROR -> log.error(line, cause)
        }
    }

    private fun describe(cause: Throwable): String =
        (listOf(cause.toString()) + cause.stackTrace.take(STACK_FRAMES).map { "    at $it" })
            .joinToString("\n")
}
