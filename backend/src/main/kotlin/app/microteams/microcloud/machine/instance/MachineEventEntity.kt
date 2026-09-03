/*
 *  Description: A machine's event log — one append-only, timestamped row per step of every lifecycle
 *               action (provision / start / shutdown / stop / delete / AI switch / AI login), with the
 *               evidence in `detail` (a Proxmox UPID, a duration, an output tail, ccproxy's raw
 *               status, an exception). It exists because `status` / `aiStatus` only say where a
 *               machine ended up, and the Spring log that said how died with its container: on
 *               2026-09-02 one machine sat in aiStatus=provisioning for 8.5 minutes and nothing
 *               recorded what its login poller saw. Rows are never updated or deleted, and there is
 *               no cascade from the machine, so a deleted machine's history stays readable.
 *
 *  Author(s):
 *      Zhifei Li    <andylizf@outlook.com>
 *
 */

package app.microteams.microcloud.machine.instance

import jakarta.persistence.*
import java.time.Instant
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

/** The lifecycle action an event belongs to. */
enum class MachineEventAction {
    /** Create on Proxmox, boot, init, AI setup — through to RUNNING. */
    PROVISION,
    START,
    SHUTDOWN,
    STOP,
    /** Teardown: the Proxmox guest, the AI registrations, the leased IP. */
    DELETE,
    /** A switch of the machine's Claude Code between newapi and ccproxy. */
    AI_SWITCH,
    /** A ccproxy subscription login, from start to ready. */
    AI_LOGIN,
}

/**
 * The step within an action. The set is deliberately small: a phase names the step, `level` and
 * `message` say how it went, `detail` carries the evidence.
 */
enum class MachineEventPhase {
    /** The action began; detail says where and with what. */
    STARTED,
    /** A Proxmox task was submitted; detail is its UPID. */
    PVE_TASK_SUBMITTED,
    /** That Proxmox task finished OK; detail is the UPID and the duration. */
    PVE_TASK_DONE,
    /** The guest accepts TCP :22; detail is how long the wait took. */
    SSH_REACHABLE,
    /** init-machine finished; detail is the tail of its output. */
    INIT_DONE,
    /** The birth registration with ccproxy: INFO when it worked, WARN when it did not. */
    CCPROXY_REGISTERED,
    /** The AI channel could not be set up (aiStatus=ERROR); the machine itself is unaffected. */
    AI_SETUP_FAILED,
    /** The machine reached RUNNING — the end of PROVISION. */
    RUNNING,
    /** ccproxy started the login; detail is the login request id and the account. */
    LOGIN_STARTED,
    /** ccproxy's reported status changed; detail is the raw status. */
    LOGIN_POLLED,
    /** The login completed: the machine holds a credential. */
    LOGIN_READY,
    /** A previous, never-completed login was cancelled so a fresh one could start. */
    LOGIN_CANCELLED,
    /** The action completed (START / SHUTDOWN / STOP / DELETE / AI_SWITCH). */
    DONE,
    /** The action failed; detail is the exception. */
    FAILED,
}

enum class MachineEventLevel {
    INFO,
    WARN,
    ERROR,
}

// No @SQLRestriction: nothing ever soft-deletes an event, so the filter would only hide a bug.
@Entity
@Table(name = "machine_event", indexes = [Index(columnList = "machine_id")])
class MachineEvent(
    @Column(name = "tenant_id", nullable = false) var tenantId: IdType? = null,
    @Column(name = "machine_id", nullable = false) var machineId: IdType? = null,
    /**
     * Record time. An Instant (not the JVM-zone LocalDateTime of BaseEntity) so `since` is exact.
     */
    @Column(nullable = false) var at: Instant? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var action: MachineEventAction? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var phase: MachineEventPhase? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var level: MachineEventLevel? = null,
    @Column(nullable = false, length = 512) var message: String? = null,
    @Column(columnDefinition = "text") var detail: String? = null,
) : BaseEntity()

interface MachineEventRepository : JpaRepository<MachineEvent, IdType> {
    fun findByMachineIdOrderByAtAscIdAsc(machineId: IdType): List<MachineEvent>

    fun findByMachineIdAndAtGreaterThanEqualOrderByAtAscIdAsc(
        machineId: IdType,
        since: Instant,
    ): List<MachineEvent>
}
