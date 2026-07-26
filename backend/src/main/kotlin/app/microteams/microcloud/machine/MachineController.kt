/*
 *  Description: The machine module's one controller. It implements the (large) MachineApi and
 *               delegates each operation group to the service that owns it, one per subpackage
 *               (proxmox / placement / network / zone / type / template / instance …). Operations
 *               not yet wired keep the generated 501 default. Authorization is a @Guard per method.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine

import app.microteams.microcloud.api.MachineApi
import app.microteams.microcloud.customer.CustomerService
import app.microteams.microcloud.machine.instance.MachineService
import app.microteams.microcloud.machine.network.NetworkService
import app.microteams.microcloud.machine.offering.OfferingService
import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.machine.proxmox.ProxmoxService
import app.microteams.microcloud.machine.template.TemplateService
import app.microteams.microcloud.machine.type.MachineTypeService
import app.microteams.microcloud.machine.zone.ZoneService
import app.microteams.microcloud.model.*
import javax.annotation.PostConstruct
import org.rucca.cheese.auth.AuthenticationService
import org.rucca.cheese.auth.AuthorizationService
import org.rucca.cheese.auth.AuthorizedAction
import org.rucca.cheese.auth.annotation.AuthInfo
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.cheese.auth.annotation.ResourceId
import org.rucca.cheese.common.persistent.IdGetter
import org.rucca.cheese.common.persistent.IdType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class MachineController(
    private val proxmoxService: ProxmoxService,
    private val placementService: PlacementService,
    private val networkService: NetworkService,
    private val machineTypeService: MachineTypeService,
    private val zoneService: ZoneService,
    private val templateService: TemplateService,
    private val machineService: MachineService,
    private val offeringService: OfferingService,
    private val customerService: CustomerService,
    private val authenticationService: AuthenticationService,
    private val authorizationService: AuthorizationService,
) : MachineApi {

    /** Super-admins hold permissions on the "tenant" resource; tenants never do. */
    private fun isSuperAdmin(): Boolean =
        authorizationService.verify(authenticationService.getToken()).permissions.any {
            it.authorizedResource.types?.contains("tenant") == true
        }

    @PostConstruct
    fun initialize() {
        authorizationService.ownerIds.register("machine", machineService::getOwnerTenant)
        // A customer_id filter on machine listing is allowed only for the caller's own customer.
        register("queried-customer-is-own-machine") { userId, authInfo ->
            when (val cid = authInfo["customerId"] as? Long) {
                null -> true
                else -> customerService.getOwnerTenant(cid) == userId
            }
        }
    }

    private fun register(name: String, fact: (IdType, Map<String, Any>) -> Boolean) {
        authorizationService.customAuthLogics.register(name) {
            userId: IdType,
            _: AuthorizedAction,
            _: String,
            _: IdType?,
            authInfo: Map<String, Any>,
            _: IdGetter?,
            _: Any? ->
            fact(userId, authInfo)
        }
    }

    private fun tenantId(): IdType = authenticationService.getCurrentUserId()

    // ---- Proxmox clusters (super-admin) ----

    @Guard("list-proxmox", "proxmox")
    override fun listProxmoxClusters(
        pageStart: Long?,
        pageSize: Int,
    ): ResponseEntity<ListProxmoxClustersResponseDTO> {
        val (items, page) = proxmoxService.listClusters(pageStart, pageSize)
        return ResponseEntity.ok(ListProxmoxClustersResponseDTO(items = items, page = page))
    }

    @Guard("create-proxmox", "proxmox")
    override fun createProxmoxCluster(
        @RequestBody createProxmoxClusterRequestDTO: CreateProxmoxClusterRequestDTO
    ): ResponseEntity<ProxmoxClusterDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(proxmoxService.createCluster(createProxmoxClusterRequestDTO))

    @Guard("get-proxmox", "proxmox")
    override fun getProxmoxCluster(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<ProxmoxClusterDTO> = ResponseEntity.ok(proxmoxService.getClusterDTO(id))

    @Guard("update-proxmox", "proxmox")
    override fun updateProxmoxCluster(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody updateProxmoxClusterRequestDTO: UpdateProxmoxClusterRequestDTO,
    ): ResponseEntity<ProxmoxClusterDTO> =
        ResponseEntity.ok(proxmoxService.updateCluster(id, updateProxmoxClusterRequestDTO))

    @Guard("delete-proxmox", "proxmox")
    override fun deleteProxmoxCluster(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<Unit> {
        proxmoxService.deleteCluster(id)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }

    @Guard("inventory-proxmox", "proxmox")
    override fun getProxmoxInventory(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<ProxmoxInventoryDTO> = ResponseEntity.ok(proxmoxService.readInventory(id))

    // ---- Placements (super-admin) ----

    @Guard("list-placement", "placement")
    override fun listPlacements(
        pageStart: Long?,
        pageSize: Int,
    ): ResponseEntity<ListPlacementsResponseDTO> {
        val (items, page) = placementService.listPlacements(pageStart, pageSize)
        return ResponseEntity.ok(ListPlacementsResponseDTO(items = items, page = page))
    }

    @Guard("create-placement", "placement")
    override fun createPlacement(
        @RequestBody createPlacementRequestDTO: CreatePlacementRequestDTO
    ): ResponseEntity<PlacementDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(placementService.createPlacement(createPlacementRequestDTO))

    @Guard("get-placement", "placement")
    override fun getPlacement(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<PlacementDTO> = ResponseEntity.ok(placementService.getPlacementDTO(id))

    @Guard("update-placement", "placement")
    override fun updatePlacement(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody updatePlacementRequestDTO: UpdatePlacementRequestDTO,
    ): ResponseEntity<PlacementDTO> =
        ResponseEntity.ok(placementService.updatePlacement(id, updatePlacementRequestDTO))

    @Guard("delete-placement", "placement")
    override fun deletePlacement(@PathVariable("id") @ResourceId id: IdType): ResponseEntity<Unit> {
        placementService.deletePlacement(id)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }

    // ---- Networks (super-admin) ----

    @Guard("list-network", "network")
    override fun listNetworks(
        pageStart: Long?,
        pageSize: Int,
        placementId: Long?,
    ): ResponseEntity<ListNetworksResponseDTO> {
        val (items, page) = networkService.listNetworks(placementId, pageStart, pageSize)
        return ResponseEntity.ok(ListNetworksResponseDTO(items = items, page = page))
    }

    @Guard("create-network", "network")
    override fun createNetwork(
        @RequestBody createNetworkRequestDTO: CreateNetworkRequestDTO
    ): ResponseEntity<NetworkDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(networkService.createNetwork(createNetworkRequestDTO))

    @Guard("get-network", "network")
    override fun getNetwork(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<NetworkDTO> = ResponseEntity.ok(networkService.getNetworkDTO(id))

    @Guard("update-network", "network")
    override fun updateNetwork(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody updateNetworkRequestDTO: UpdateNetworkRequestDTO,
    ): ResponseEntity<NetworkDTO> =
        ResponseEntity.ok(networkService.updateNetwork(id, updateNetworkRequestDTO))

    @Guard("delete-network", "network")
    override fun deleteNetwork(@PathVariable("id") @ResourceId id: IdType): ResponseEntity<Unit> {
        networkService.deleteNetwork(id)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }

    // ---- Machine types (admin writes; tenants + admin read) ----

    @Guard("list-machine-type", "machine-type")
    override fun listMachineTypes(
        pageStart: Long?,
        pageSize: Int,
    ): ResponseEntity<ListMachineTypesResponseDTO> {
        val (items, page) = machineTypeService.listTypes(pageStart, pageSize)
        return ResponseEntity.ok(ListMachineTypesResponseDTO(items = items, page = page))
    }

    @Guard("get-machine-type", "machine-type")
    override fun getMachineType(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<MachineTypeDTO> = ResponseEntity.ok(machineTypeService.getTypeDTO(id))

    @Guard("create-machine-type", "machine-type")
    override fun createMachineType(
        @RequestBody createMachineTypeRequestDTO: CreateMachineTypeRequestDTO
    ): ResponseEntity<MachineTypeDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(machineTypeService.createType(createMachineTypeRequestDTO))

    @Guard("update-machine-type", "machine-type")
    override fun updateMachineType(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody updateMachineTypeRequestDTO: UpdateMachineTypeRequestDTO,
    ): ResponseEntity<MachineTypeDTO> =
        ResponseEntity.ok(machineTypeService.updateType(id, updateMachineTypeRequestDTO))

    @Guard("delete-machine-type", "machine-type")
    override fun deleteMachineType(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<Unit> {
        machineTypeService.deleteType(id)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }

    // ---- Zones (admin writes; tenants + admin read) ----

    @Guard("list-zone", "zone")
    override fun listZones(pageStart: Long?, pageSize: Int): ResponseEntity<ListZonesResponseDTO> {
        val (items, page) = zoneService.listZones(pageStart, pageSize)
        return ResponseEntity.ok(ListZonesResponseDTO(items = items, page = page))
    }

    @Guard("get-zone", "zone")
    override fun getZone(@PathVariable("id") @ResourceId id: IdType): ResponseEntity<ZoneDTO> =
        ResponseEntity.ok(zoneService.getZoneDTO(id))

    @Guard("create-zone", "zone")
    override fun createZone(
        @RequestBody createZoneRequestDTO: CreateZoneRequestDTO
    ): ResponseEntity<ZoneDTO> =
        ResponseEntity.status(HttpStatus.CREATED).body(zoneService.createZone(createZoneRequestDTO))

    @Guard("update-zone", "zone")
    override fun updateZone(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody updateZoneRequestDTO: UpdateZoneRequestDTO,
    ): ResponseEntity<ZoneDTO> = ResponseEntity.ok(zoneService.updateZone(id, updateZoneRequestDTO))

    @Guard("delete-zone", "zone")
    override fun deleteZone(@PathVariable("id") @ResourceId id: IdType): ResponseEntity<Unit> {
        zoneService.deleteZone(id)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }

    // ---- Templates (tenants + admin list; admin uploads to placements) ----

    @Guard("list-template", "template")
    override fun listMachineTemplates(
        pageStart: Long?,
        pageSize: Int,
    ): ResponseEntity<ListMachineTemplatesResponseDTO> {
        val (items, page) = templateService.listTemplates(pageStart, pageSize)
        return ResponseEntity.ok(ListMachineTemplatesResponseDTO(items = items, page = page))
    }

    @Guard("list-template-upload", "template")
    override fun listTemplateUploads(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<ListTemplateUploadsResponseDTO> {
        // The endpoint carries no page params; a placement count in the thousands is not realistic.
        val (items, page) = templateService.listUploads(id, null, 10_000)
        return ResponseEntity.ok(ListTemplateUploadsResponseDTO(items = items, page = page))
    }

    @Guard("upload-template", "template")
    override fun uploadTemplate(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody uploadTemplateRequestDTO: UploadTemplateRequestDTO,
    ): ResponseEntity<TemplateUploadDTO> =
        ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(templateService.startUpload(id, uploadTemplateRequestDTO.placementId))

    // ---- Machines (tenant-facing; scoped to the caller's tenant) ----

    @Guard("list-machine", "machine")
    override fun listMachines(
        pageStart: Long?,
        pageSize: Int,
        @AuthInfo("customerId") customerId: Long?,
    ): ResponseEntity<ListMachinesResponseDTO> {
        val (items, page) = machineService.listMachines(tenantId(), customerId, pageStart, pageSize)
        return ResponseEntity.ok(ListMachinesResponseDTO(items = items, page = page))
    }

    @Guard("create-machine", "machine")
    override fun createMachine(
        @RequestBody createMachineRequestDTO: CreateMachineRequestDTO
    ): ResponseEntity<MachineDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(machineService.createMachine(tenantId(), createMachineRequestDTO))

    @Guard("get-machine", "machine")
    override fun getMachine(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<MachineDTO> = ResponseEntity.ok(machineService.getMachineDTO(tenantId(), id))

    @Guard("start-machine", "machine")
    override fun startMachine(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<MachineDTO> =
        ResponseEntity.accepted().body(machineService.startMachine(tenantId(), id))

    @Guard("stop-machine", "machine")
    override fun stopMachine(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<MachineDTO> =
        ResponseEntity.accepted().body(machineService.stopMachine(tenantId(), id))

    @Guard("delete-machine", "machine")
    override fun deleteMachine(@PathVariable("id") @ResourceId id: IdType): ResponseEntity<Unit> {
        machineService.deleteMachine(tenantId(), id)
        return ResponseEntity(HttpStatus.ACCEPTED)
    }

    // ---- Offerings (admin composes per tenant; tenant lists its own) ----

    @Guard("list-offering", "offering")
    override fun listOfferings(
        pageStart: Long?,
        pageSize: Int,
        tenantId: Long?,
    ): ResponseEntity<ListOfferingsResponseDTO> {
        // Super-admin: all, or filtered by tenant_id. Tenant: forced to its own (query ignored).
        val scope = if (isSuperAdmin()) tenantId else tenantId()
        val (items, page) = offeringService.listOfferings(scope, pageStart, pageSize)
        return ResponseEntity.ok(ListOfferingsResponseDTO(items = items, page = page))
    }

    @Guard("create-offering", "offering")
    override fun createOffering(
        @RequestBody createOfferingRequestDTO: CreateOfferingRequestDTO
    ): ResponseEntity<OfferingDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(offeringService.createOffering(createOfferingRequestDTO))

    @Guard("get-offering", "offering")
    override fun getOffering(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<OfferingDTO> = ResponseEntity.ok(offeringService.getOfferingDTO(id))

    @Guard("update-offering", "offering")
    override fun updateOffering(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody updateOfferingRequestDTO: UpdateOfferingRequestDTO,
    ): ResponseEntity<OfferingDTO> =
        ResponseEntity.ok(offeringService.updateOffering(id, updateOfferingRequestDTO))

    @Guard("delete-offering", "offering")
    override fun deleteOffering(@PathVariable("id") @ResourceId id: IdType): ResponseEntity<Unit> {
        offeringService.deleteOffering(id)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }
}
