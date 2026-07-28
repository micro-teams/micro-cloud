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
import app.microteams.microcloud.machine.network.NetworkService
import app.microteams.microcloud.machine.offering.OfferingService
import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.machine.placement.PlacementStatus
import app.microteams.microcloud.machine.placement.effectiveKind
import app.microteams.microcloud.machine.type.MachineType
import app.microteams.microcloud.machine.zone.ZoneService
import app.microteams.microcloud.model.*
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

    fun getOwnerTenant(id: IdType): IdType =
        machineRepository.findById(id).orElseThrow { NotFoundError("machine", id) }.tenantId!!

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

        // AI mode: every machine defaults to NEWAPI (fully automatic — a per-machine relay token,
        // usable immediately, no human step). ccproxy is only ever reached via a super-admin
        // switch,
        // never chosen at create. If newapi isn't wired, the machine simply gets no AI.

        // Flush the insert so @CreationTimestamp is assigned before we mutate + save again for the
        // IP.
        val machine =
            machineRepository.saveAndFlush(
                Machine(
                    tenantId = tenantId,
                    customerId = request.customerId,
                    accountId = request.accountId,
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
                    aiMode = app.microteams.microcloud.machine.ai.AiMode.NEWAPI,
                    aiStatus = app.microteams.microcloud.machine.ai.AiStatus.PROVISIONING,
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
