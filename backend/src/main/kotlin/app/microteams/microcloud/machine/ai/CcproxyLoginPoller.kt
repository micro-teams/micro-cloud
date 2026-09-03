/*
 *  Description: Background poller for a ccproxy subscription login. A separate bean so the @Async
 *               call crosses a Spring proxy boundary (self-invocation would run inline). A human
 *               login-operator completes the OAuth on ccproxy's side, so this can take minutes; it
 *               watches the ccproxy machine until it holds a credential (READY) and lands aiStatus.
 *               Every change in what ccproxy reports goes to the machine's event log: a machine
 *               that sits in aiStatus=provisioning for minutes must show what its poller saw.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

import app.microteams.microcloud.common.config.MicroCloudConfig
import app.microteams.microcloud.machine.instance.MachineEventAction.AI_LOGIN
import app.microteams.microcloud.machine.instance.MachineEventLevel.ERROR
import app.microteams.microcloud.machine.instance.MachineEventPhase.FAILED
import app.microteams.microcloud.machine.instance.MachineEventPhase.LOGIN_POLLED
import app.microteams.microcloud.machine.instance.MachineEventPhase.LOGIN_READY
import app.microteams.microcloud.machine.instance.MachineEventRecorder
import app.microteams.microcloud.machine.instance.MachineRepository
import org.rucca.cheese.common.persistent.IdType
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class CcproxyLoginPoller(
    private val machineRepository: MachineRepository,
    private val ccproxyClient: CcproxyClient,
    private val config: MicroCloudConfig,
    private val events: MachineEventRecorder,
) {
    private val log = LoggerFactory.getLogger(CcproxyLoginPoller::class.java)

    @Async
    fun pollLoginToReady(machineId: IdType, ccId: Long) {
        val machine = machineRepository.findById(machineId).orElse(null) ?: return
        val timeoutSeconds = config.ccproxy.statusTimeoutSeconds
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        var last: CcproxyMachine? = null
        try {
            while (System.currentTimeMillis() < deadline) {
                val m = ccproxyClient.getMachine(ccId)
                if (m.status != last?.status) {
                    events.record(
                        machine,
                        AI_LOGIN,
                        LOGIN_POLLED,
                        "ccproxy reports the machine as ${m.status}",
                        detail = describe(m),
                    )
                }
                last = m
                if (m.status == "ready" && m.hasCredential) {
                    updateStatus(machineId, AiStatus.READY)
                    events.record(
                        machine,
                        AI_LOGIN,
                        LOGIN_READY,
                        "ccproxy login completed; the machine holds a subscription credential",
                        detail = describe(m),
                    )
                    return
                }
                Thread.sleep(5000)
            }
            updateStatus(machineId, AiStatus.ERROR)
            events.record(
                machine,
                AI_LOGIN,
                FAILED,
                "ccproxy login did not reach ready within $timeoutSeconds s; last seen ${last?.status}",
                ERROR,
                detail = last?.let { describe(it) },
            )
        } catch (e: Exception) {
            updateStatus(machineId, AiStatus.ERROR)
            events.record(
                machine,
                AI_LOGIN,
                FAILED,
                "polling the ccproxy login failed: ${e.message}",
                ERROR,
                detail = last?.let { describe(it) },
                cause = e,
            )
        }
    }

    /** ccproxy's machine state as MicroCloud read it, for an event's detail. */
    private fun describe(m: CcproxyMachine): String =
        "ccproxyMachineId=${m.id}\nstatus=${m.status}\nhasCredential=${m.hasCredential}\n" +
            "loginRequestId=${m.currentLoginRequestId}\n" +
            "credentialExpiresAt=${m.credentialExpiresAt}\nerror=${m.error}"

    @Transactional
    fun updateStatus(machineId: IdType, status: AiStatus) {
        machineRepository.findById(machineId).ifPresent {
            it.aiStatus = status
            machineRepository.save(it)
        }
    }
}
