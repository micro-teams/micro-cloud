/*
 *  Description: Background poller for a ccproxy subscription login. A separate bean so the @Async
 *               call crosses a Spring proxy boundary (self-invocation would run inline). A human
 *               login-operator completes the OAuth on ccproxy's side, so this can take minutes; it
 *               watches the ccproxy machine until it holds a credential (READY) and lands aiStatus.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

import app.microteams.microcloud.common.config.MicroCloudConfig
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
) {
    private val log = LoggerFactory.getLogger(CcproxyLoginPoller::class.java)

    @Async
    fun pollLoginToReady(machineId: IdType, ccId: Long) {
        val deadline = System.currentTimeMillis() + config.ccproxy.statusTimeoutSeconds * 1000
        try {
            while (System.currentTimeMillis() < deadline) {
                val m = ccproxyClient.getMachine(ccId)
                if (m.status == "ready" && m.hasCredential) {
                    updateStatus(machineId, AiStatus.READY)
                    log.info("machine {} switched to ccproxy (ready)", machineId)
                    return
                }
                Thread.sleep(5000)
            }
            log.warn("machine {} ccproxy login did not reach ready in time", machineId)
            updateStatus(machineId, AiStatus.ERROR)
        } catch (e: Exception) {
            log.warn("polling ccproxy login for machine {} failed: {}", machineId, e.message)
            updateStatus(machineId, AiStatus.ERROR)
        }
    }

    @Transactional
    fun updateStatus(machineId: IdType, status: AiStatus) {
        machineRepository.findById(machineId).ifPresent {
            it.aiStatus = status
            machineRepository.save(it)
        }
    }
}
