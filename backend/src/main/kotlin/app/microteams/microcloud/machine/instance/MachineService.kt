/*
 *  Description: Tenant-facing machine provisioning. createMachine validates ownership (customer +
 *               account belong to the caller), the referenced type/zone/template, and the requested
 *               spec against the type's ranges; then it selects a placement (from the type, narrowed
 *               by the zone) that has a network with a free address, leases an IP, and records the
 *               machine as PROVISIONING. The actual Proxmox `pct create` + IP config + SSH/Claude
 *               init runs in an async worker (MachineProvisioner) that drives status to RUNNING/ERROR.
 *               start / stop / delete move the state (releasing IPs on delete) via the provisioner.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.instance

import app.microteams.microcloud.account.AccountService
import app.microteams.microcloud.common.helper.PageHelper
import app.microteams.microcloud.customer.CustomerService
import app.microteams.microcloud.machine.ai.AiMode
import app.microteams.microcloud.machine.ai.AiStatus
import app.microteams.microcloud.machine.ai.CcproxyClient
import app.microteams.microcloud.machine.network.NetworkService
import app.microteams.microcloud.machine.offering.OfferingService
import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.machine.placement.PlacementStatus
import app.microteams.microcloud.machine.placement.effectiveKind
import app.microteams.microcloud.machine.type.MachineType
import app.microteams.microcloud.machine.zone.ZoneService
import app.microteams.microcloud.model.*
import java.time.Instant
import java.time.ZoneOffset
import org.rucca.cheese.common.error.BadRequestError
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

fun Machine.toDTO() =
    MachineDTO(
        id = this.id!!,
        customerId = this.customerId!!,
        accountId = this.accountId!!,
        newapiAccountId = this.effectiveNewapiAccountId,
        ccproxyAccountId = this.effectiveCcproxyAccountId,
        hostname = this.hostname!!,
        offeringId = this.offeringId!!,
        typeId = this.typeId!!,
        zoneId = this.zoneId,
        templateId = this.templateId!!,
        apiKeyId = this.apiKeyId,
        cores = this.cores!!,
        memoryMb = this.memoryMb!!,
        diskGb = this.diskGb!!,
        ip = this.ip,
        aiMode = (this.aiMode ?: app.microteams.microcloud.machine.ai.AiMode.NONE).name.lowercase(),
        aiStatus =
            (this.aiStatus ?: app.microteams.microcloud.machine.ai.AiStatus.DISABLED)
                .name
                .lowercase(),
        status =
            when (this.status) {
                MachineStatus.PROVISIONING -> MachineStatusDTO.provisioning
                MachineStatus.STARTING -> MachineStatusDTO.starting
                MachineStatus.RUNNING -> MachineStatusDTO.running
                MachineStatus.STOPPING -> MachineStatusDTO.stopping
                MachineStatus.STOPPED -> MachineStatusDTO.stopped
                MachineStatus.DELETING -> MachineStatusDTO.deleting
                MachineStatus.DELETED -> MachineStatusDTO.deleted
                MachineStatus.ERROR -> MachineStatusDTO.error
            },
        createdAt = this.createdAt?.atOffset(java.time.ZoneOffset.UTC),
    )

fun MachineEvent.toDTO() =
    MachineEventDTO(
        id = this.id!!,
        machineId = this.machineId!!,
        at = this.at!!.atOffset(ZoneOffset.UTC),
        action = MachineEventActionDTO.valueOf(this.action!!.name),
        phase = MachineEventPhaseDTO.valueOf(this.phase!!.name),
        level = MachineEventLevelDTO.valueOf(this.level!!.name),
        message = this.message!!,
        detail = this.detail,
    )

@Service
@Transactional
class MachineService(
    private val machineRepository: MachineRepository,
    private val customerService: CustomerService,
    private val accountService: AccountService,
    private val offeringService: OfferingService,
    private val zoneService: ZoneService,
    private val placementService: PlacementService,
    private val networkService: NetworkService,
    private val templateUploadRepository:
        app.microteams.microcloud.machine.template.TemplateUploadRepository,
    private val templateRepository:
        app.microteams.microcloud.machine.template.MachineTemplateRepository,
    private val provisioner: MachineProvisioner,
    private val ccproxyClient: CcproxyClient,
    private val eventRepository: MachineEventRepository,
) {
    private companion object {
        // RFC1123 hostname: dot-separated labels of [a-zA-Z0-9-], no leading/trailing hyphen, each
        // label ≤63, whole name ≤253. Matches what Proxmox accepts for a CT hostname / VM name.
        val HOSTNAME_RE =
            Regex(
                "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)" +
                    "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"
            )
    }

    fun getMachine(tenantId: IdType, id: IdType): Machine {
        val machine = machineRepository.findById(id).orElseThrow { NotFoundError("machine", id) }
        if (machine.tenantId != tenantId) throw NotFoundError("machine", id)
        return machine
    }

    fun getMachineDTO(tenantId: IdType, id: IdType): MachineDTO = getMachine(tenantId, id).toDTO()

    /**
     * The owning tenant, for the "owned" authorization predicate. Deliberately sees soft-deleted
     * machines: a deleted machine's event log is still its tenant's to read, so the guard must
     * resolve the owner rather than 404 — the lookups behind every other endpoint still 404 on a
     * deleted machine themselves.
     */
    fun getOwnerTenant(id: IdType): IdType =
        machineRepository.findTenantIdIncludingDeleted(id) ?: throw NotFoundError("machine", id)

    /**
     * The machine's event log, oldest first. [tenantId] null = the super-admin reading any machine;
     * otherwise the machine must belong to that tenant (404 if not). Works for a deleted machine:
     * only the owner lookup is needed, and that one sees deleted rows.
     */
    fun listEvents(
        tenantId: IdType?,
        id: IdType,
        since: Instant?,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<MachineEventDTO>, PageDTO> {
        val owner = getOwnerTenant(id)
        if (tenantId != null && owner != tenantId) throw NotFoundError("machine", id)
        val all =
            if (since == null) eventRepository.findByMachineIdOrderByAtAscIdAsc(id)
            else eventRepository.findByMachineIdAndAtGreaterThanEqualOrderByAtAscIdAsc(id, since)
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    /** The requested AI mode, NEWAPI when the caller says nothing; a 400 for a word we lack. */
    private fun parseAiMode(raw: String?): AiMode {
        val word = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return AiMode.NEWAPI
        return AiMode.entries.firstOrNull { it.name.equals(word, ignoreCase = true) }
            ?: throw BadRequestError(
                "aiMode must be one of ${AiMode.entries.joinToString { it.name.lowercase() }}"
            )
    }

    private fun requireInRange(value: Int, min: Int, max: Int, label: String) {
        if (value < min || value > max)
            throw BadRequestError("$label must be within $min..$max for this type")
    }

    fun createMachine(tenantId: IdType, request: CreateMachineRequestDTO): MachineDTO {
        // The hostname is honored verbatim as BOTH the Proxmox object name (pct `hostname` / qm VM
        // `name`) and the guest's internal hostname (LXC sets it directly; a VM's cloud-init
        // derives
        // it from the VM name). Proxmox/DNS can't represent every string, so reject anything that
        // isn't a valid RFC1123 hostname up front (a clean 400) instead of letting the async worker
        // fail against Proxmox. Labels are letters/digits/hyphens (no leading/trailing hyphen, ≤63
        // each), dot-separated, ≤253 total — notably NO underscores or spaces.
        if (!HOSTNAME_RE.matches(request.hostname))
            throw BadRequestError(
                "hostname must be a valid RFC1123 hostname: dot-separated labels of letters, " +
                    "digits and hyphens (no leading/trailing hyphen, ≤63 chars each, ≤253 total; " +
                    "no underscores or spaces)"
            )
        // Ownership: the customer and account must belong to the calling tenant (404 otherwise).
        customerService.getCustomer(tenantId, request.customerId)
        accountService.getAccount(tenantId, request.accountId)
        // Per-stream billing accounts: compute / newapi / ccproxy. The tenant may pass one account
        // and leave the AI ones blank (they default to the compute account), or split them. Each
        // provided account must also belong to the tenant.
        val newapiAccountId = request.newapiAccountId ?: request.accountId
        val ccproxyAccountId = request.ccproxyAccountId ?: request.accountId
        if (newapiAccountId != request.accountId)
            accountService.getAccount(tenantId, newapiAccountId)
        if (ccproxyAccountId != request.accountId)
            accountService.getAccount(tenantId, ccproxyAccountId)
        // The offering (machine type + zone + template) must be one this tenant may use.
        val offering = offeringService.getUsableForTenant(tenantId, request.offeringId)
        val type = offeringService.typeOf(offering)

        requireInRange(request.cores, type.coresMin!!, type.coresMax!!, "cores")
        requireInRange(request.memoryMb, type.memoryMbMin!!, type.memoryMbMax!!, "memoryMb")
        requireInRange(request.diskGb, type.diskGbMin!!, type.diskGbMax!!, "diskGb")

        val (placementId, network) =
            selectPlacementAndNetwork(type, offering.zoneId!!, offering.templateId!!)

        // The machine's form (proxmox/lxc, proxmox/vm) follows from the placement it lands on; the
        // provisioner reads it from the placement. Nothing kind-related is stored on the machine.

        // AI mode, decided at create. NEWAPI is the default (fully automatic — a per-machine
        // relay token, usable immediately, no human step). CCPROXY starts the subscription login
        // as soon as the machine runs, instead of provisioning newapi first and being switched
        // later, which set the AI channel up twice. NONE wires no AI at all.
        val aiMode = parseAiMode(request.aiMode)
        if (aiMode == AiMode.CCPROXY && !ccproxyClient.isConfigured())
            throw BadRequestError("aiMode ccproxy: ccproxy is not configured on this deployment")

        // Flush the insert so @CreationTimestamp is assigned before we mutate + save again for the
        // IP.
        val machine =
            machineRepository.saveAndFlush(
                Machine(
                    tenantId = tenantId,
                    customerId = request.customerId,
                    accountId = request.accountId,
                    newapiAccountId = newapiAccountId,
                    ccproxyAccountId = ccproxyAccountId,
                    hostname = request.hostname,
                    offeringId = offering.id!!,
                    typeId = type.id!!,
                    zoneId = offering.zoneId,
                    templateId = offering.templateId,
                    apiKeyId = request.apiKeyId,
                    cores = request.cores,
                    memoryMb = request.memoryMb,
                    diskGb = request.diskGb,
                    placementId = placementId,
                    networkId = network,
                    loginUser = request.user,
                    sshPubkey = request.sshPubkey,
                    aiMode = aiMode,
                    aiStatus =
                        if (aiMode == AiMode.NONE) AiStatus.DISABLED else AiStatus.PROVISIONING,
                )
            )
        machine.ip = networkService.allocateIp(network, machine.id!!)
        val saved = machineRepository.save(machine)
        // Provision on Proxmox after this transaction commits, so the async worker sees the row.
        // It drives PROVISIONING -> RUNNING / ERROR: pct create on the placement with the leased IP
        // + gateway + bridge, then init-machine over SSH (login user + sshPubkey + Claude config).
        afterCommit { provisioner.provision(saved.id!!) }
        return saved.toDTO()
    }

    /**
     * Pick a placement backing the type AND in the zone that is active, has the template uploaded,
     * and has a network with a free address; return it with the chosen network. Fails otherwise.
     */
    private fun selectPlacementAndNetwork(
        type: MachineType,
        zoneId: IdType,
        templateId: IdType,
    ): Pair<IdType, IdType> {
        val zone = zoneService.getZone(zoneId)
        val templateKind = templateRepository.findById(templateId).map { it.kind }.orElse(null)
        val candidateIds = type.placementIds.filter { it in zone.placementIds }
        for (placementId in candidateIds) {
            val placement = placementService.getPlacement(placementId)
            if (placement.status != PlacementStatus.ACTIVE) continue
            // The placement must host the template's kind (an LXC template can't run on a VM
            // placement). This is normally implied — a template only uploads to matching-kind
            // placements — but enforce it explicitly during selection.
            if (templateKind != null && placement.effectiveKind != templateKind) continue
            val uploaded =
                templateUploadRepository
                    .findByTemplateIdAndPlacementId(templateId, placementId)
                    .filter {
                        it.status ==
                            app.microteams.microcloud.machine.template.TemplateUploadStatus.DONE
                    }
                    .isPresent
            if (!uploaded) continue
            val network = networkService.networksWithFreeIp(placementId).firstOrNull() ?: continue
            return placementId to network.id!!
        }
        throw BadRequestError(
            "no placement with the template uploaded and free capacity for this offering"
        )
    }

    fun listMachines(
        tenantId: IdType,
        customerId: IdType?,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<MachineDTO>, PageDTO> {
        val all =
            (if (customerId == null) machineRepository.findByTenantId(tenantId)
                else machineRepository.findByTenantIdAndCustomerId(tenantId, customerId))
                .sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun startMachine(tenantId: IdType, id: IdType): MachineDTO {
        val machine = getMachine(tenantId, id)
        if (machine.status == MachineStatus.STOPPED) {
            machine.status = MachineStatus.STARTING
            machineRepository.save(machine)
            afterCommit { provisioner.startCt(id) } // async pct start -> RUNNING / ERROR
        }
        return machine.toDTO()
    }

    /** Graceful shutdown (ACPI): the guest flushes its FS and powers off cleanly. Preferred. */
    fun shutdownMachine(tenantId: IdType, id: IdType): MachineDTO {
        val machine = getMachine(tenantId, id)
        if (machine.status == MachineStatus.RUNNING) {
            machine.status = MachineStatus.STOPPING
            machineRepository.save(machine)
            afterCommit { provisioner.shutdownCt(id) } // async graceful shutdown -> STOPPED / ERROR
        }
        return machine.toDTO()
    }

    /** HARD stop (pull the plug): no FS flush. Force path; prefer [shutdownMachine]. */
    fun stopMachine(tenantId: IdType, id: IdType): MachineDTO {
        val machine = getMachine(tenantId, id)
        if (machine.status == MachineStatus.RUNNING) {
            machine.status = MachineStatus.STOPPING
            machineRepository.save(machine)
            afterCommit { provisioner.stopCt(id) } // async pct stop -> STOPPED / ERROR
        }
        return machine.toDTO()
    }

    fun deleteMachine(tenantId: IdType, id: IdType): MachineDTO {
        val machine = getMachine(tenantId, id)
        machine.status = MachineStatus.DELETING
        machineRepository.save(machine)
        // async: destroy the CT, release its IP, remove the row (or land ERROR on failure).
        afterCommit { provisioner.destroyCt(id) }
        return machine.toDTO()
    }

    /** Run [action] after the current transaction commits, or immediately if none is active. */
    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = action()
                }
            )
        } else {
            action()
        }
    }
}
