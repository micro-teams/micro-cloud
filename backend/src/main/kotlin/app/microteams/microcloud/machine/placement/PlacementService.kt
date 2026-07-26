/*
 *  Description: Super-admin management of placements: create / list / get / update / delete. A
 *               placement references a Proxmox cluster (validated on create); deletion is refused
 *               while networks still bind to it (guard added once networks exist).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.placement

import app.microteams.microcloud.common.helper.PageHelper
import app.microteams.microcloud.machine.proxmox.ProxmoxService
import app.microteams.microcloud.model.*
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun Placement.toDTO() =
    PlacementDTO(
        id = this.id!!,
        name = this.name!!,
        clusterId = this.clusterId!!,
        node = this.node!!,
        pool = this.pool!!,
        storage = this.storage!!,
        status =
            when (this.status) {
                PlacementStatus.ACTIVE -> PlacementStatusDTO.active
                PlacementStatus.DISABLED -> PlacementStatusDTO.disabled
            },
        createdAt = this.createdAt?.atOffset(java.time.ZoneOffset.UTC),
    )

fun PlacementStatusDTO.toEntity() =
    when (this) {
        PlacementStatusDTO.active -> PlacementStatus.ACTIVE
        PlacementStatusDTO.disabled -> PlacementStatus.DISABLED
    }

@Service
@Transactional
class PlacementService(
    private val placementRepository: PlacementRepository,
    private val proxmoxService: ProxmoxService,
) {
    fun getPlacement(id: IdType): Placement =
        placementRepository.findById(id).orElseThrow { NotFoundError("placement", id) }

    fun getPlacementDTO(id: IdType): PlacementDTO = getPlacement(id).toDTO()

    fun createPlacement(request: CreatePlacementRequestDTO): PlacementDTO {
        proxmoxService.getCluster(request.clusterId) // 404 if the cluster is unknown
        return placementRepository
            .save(
                Placement(
                    name = request.name,
                    clusterId = request.clusterId,
                    node = request.node,
                    pool = request.pool,
                    storage = request.storage,
                )
            )
            .toDTO()
    }

    fun listPlacements(pageStart: IdType?, pageSize: Int): Pair<List<PlacementDTO>, PageDTO> {
        val all = placementRepository.findAll().sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun updatePlacement(id: IdType, request: UpdatePlacementRequestDTO): PlacementDTO {
        val placement = getPlacement(id)
        request.name?.let { placement.name = it }
        request.storage?.let { placement.storage = it }
        request.status?.let { placement.status = it.toEntity() }
        return placementRepository.save(placement).toDTO()
    }

    fun deletePlacement(id: IdType) {
        val placement = getPlacement(id)
        // TODO: refuse deletion while networks bind to this placement (once networks exist).
        placementRepository.delete(placement)
    }
}
