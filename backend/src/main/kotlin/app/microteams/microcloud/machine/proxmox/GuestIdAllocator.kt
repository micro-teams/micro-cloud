/*
 *  Description: Hands out Proxmox guest ids (vmids) so that two creates running at the same time
 *               never get the same one. Proxmox's /cluster/nextid only reports the lowest id no
 *               guest holds yet, so two creates that ask before either has submitted its guest both
 *               get it, and the second create fails ("144.conf File exists", machine 5290 on
 *               2026-10-20, a VM and an LXC created together). Each id is claimed with the same
 *               transaction-scoped advisory lock that guards every task on that guest, and re-checked
 *               against the cluster once claimed.
 *
 *  Author(s):
 *      Zhifei Li       <andylizf@outlook.com>
 *
 */

package app.microteams.microcloud.machine.proxmox

import app.microteams.microcloud.machine.instance.MachineRepository
import org.springframework.stereotype.Component

@Component
class GuestIdAllocator(
    private val proxmoxClient: ProxmoxClient,
    private val machineRepository: MachineRepository,
) {
    /**
     * A vmid no guest in [cluster] holds and no other MicroCloud transaction is creating, locked
     * for the caller's transaction (which must be open, and must last until the guest's create task
     * has been submitted). Starts at Proxmox's next free id and walks up past every id that another
     * create has claimed, or that a create which claimed it earlier has since used.
     *
     * Guests created outside MicroCloud take no lock; Proxmox itself refuses a second guest with an
     * id already in use, so such a collision fails the create instead of sharing a guest.
     */
    fun allocate(cluster: ProxmoxCluster): Int {
        var vmid = proxmoxClient.nextVmid(cluster)
        repeat(MAX_CANDIDATES) {
            // Lock first, then ask the cluster: a create that held this id may have committed
            // its guest between our nextid and the lock, and only the cluster knows that.
            if (
                machineRepository.tryAdvisoryLock(guestLockKey(cluster, vmid)) &&
                    !proxmoxClient.vmidTaken(cluster, vmid)
            )
                return vmid
            vmid++
        }
        error("no free vmid among $MAX_CANDIDATES candidates from Proxmox's next id")
    }

    private companion object {
        const val MAX_CANDIDATES = 64
    }
}

/** The advisory-lock key held while a guest is created or acted on (see GuestIdAllocator). */
fun guestLockKey(cluster: ProxmoxCluster, vmid: Int) = "microcloud-guest:${cluster.id}:$vmid"
