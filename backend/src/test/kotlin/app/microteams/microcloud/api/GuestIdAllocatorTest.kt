/*
 *  Description: Integration test for guest id allocation against the real Postgres advisory locks.
 *               Proxmox's next free id is the same for every create that asks before any of them
 *               has submitted its guest; on 2026-10-20 a VM and an LXC created together both got
 *               144 and the VM's clone failed ("144.conf File exists", machine 5290). Two
 *               allocations whose transactions overlap must get different ids, and an id whose
 *               guest landed after Proxmox reported it free must be skipped.
 *
 *  Author(s):
 *      Zhifei Li       <andylizf@outlook.com>
 *
 */

package app.microteams.microcloud.api

import app.microteams.microcloud.machine.proxmox.GuestIdAllocator
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.proxmox.ProxmoxCluster
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
class GuestIdAllocatorTest
@Autowired
constructor(
    private val allocator: GuestIdAllocator,
    transactionManager: PlatformTransactionManager,
) {
    @MockkBean(relaxed = true) private lateinit var proxmoxClient: ProxmoxClient

    private val tx = TransactionTemplate(transactionManager)

    /** A cluster id of its own per test, so lock keys never meet another test's. */
    private fun cluster() = ProxmoxCluster(name = "lab").apply { id = System.nanoTime() }

    @Test
    fun overlappingCreatesGetDifferentIds() {
        val cluster = cluster()
        every { proxmoxClient.nextVmid(any()) } returns 144
        every { proxmoxClient.vmidTaken(any(), any()) } returns false

        val firstHolds = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        // The first create keeps its transaction (and so its claim) open, as a provision does
        // until its guest exists, while the second allocates.
        val first =
            CompletableFuture.supplyAsync {
                tx.execute {
                    val vmid = allocator.allocate(cluster)
                    firstHolds.countDown()
                    secondDone.await(10, TimeUnit.SECONDS)
                    vmid
                }
            }
        firstHolds.await(10, TimeUnit.SECONDS)
        val second =
            CompletableFuture.supplyAsync { tx.execute { allocator.allocate(cluster) } }
                .get(10, TimeUnit.SECONDS)
        secondDone.countDown()

        assertEquals(144, first.get(10, TimeUnit.SECONDS))
        assertEquals(145, second)
    }

    @Test
    fun anIdWhoseGuestLandedMeanwhileIsSkipped() {
        // Proxmox said 144 was free, but the create that held it has since committed its guest.
        val cluster = cluster()
        every { proxmoxClient.nextVmid(any()) } returns 144
        every { proxmoxClient.vmidTaken(any(), 144) } returns true
        every { proxmoxClient.vmidTaken(any(), 145) } returns false

        assertEquals(145, tx.execute { allocator.allocate(cluster) })
    }

    @Test
    fun theClaimEndsWithTheTransaction() {
        // Once a create's transaction is over, its guest (if any) exists and Proxmox reports the
        // id taken; if it made none, the id is free to hand out again.
        val cluster = cluster()
        every { proxmoxClient.nextVmid(any()) } returns 144
        every { proxmoxClient.vmidTaken(any(), any()) } returns false

        assertEquals(144, tx.execute { allocator.allocate(cluster) })
        assertEquals(144, tx.execute { allocator.allocate(cluster) })
    }
}
