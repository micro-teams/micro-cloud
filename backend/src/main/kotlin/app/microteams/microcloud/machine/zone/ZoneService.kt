/*
 *  Description: Zone management: admin create / update / delete, tenant-and-admin read. Create/update
 *               validate that every referenced placement exists.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.zone

import app.microteams.microcloud.common.helper.PageHelper
import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.model.*
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun Zone.toDTO() =
    ZoneDTO(
        id = this.id!!,
        name = this.name!!,
        description = this.description,
        placementIds = this.placementIds.toList(),
        status =
            when (this.status) {
                ZoneStatus.ACTIVE -> ZoneStatusDTO.active
                ZoneStatus.DISABLED -> ZoneStatusDTO.disabled
            },
        createdAt = this.createdAt?.atOffset(java.time.ZoneOffset.UTC),
    )

fun ZoneStatusDTO.toEntity() =
    when (this) {
        ZoneStatusDTO.active -> ZoneStatus.ACTIVE
        ZoneStatusDTO.disabled -> ZoneStatus.DISABLED
    }

@Service
@Transactional
class ZoneService(
    private val zoneRepository: ZoneRepository,
    private val placementService: PlacementService,
) {
    fun getZone(id: IdType): Zone =
        zoneRepository.findById(id).orElseThrow { NotFoundError("zone", id) }

    fun getZoneDTO(id: IdType): ZoneDTO = getZone(id).toDTO()

    private fun validatePlacements(placementIds: List<IdType>) {
        placementIds.forEach { placementService.getPlacement(it) } // 404 on any unknown placement
    }

    fun createZone(request: CreateZoneRequestDTO): ZoneDTO {
        validatePlacements(request.placementIds)
        return zoneRepository
            .save(
                Zone(
                    name = request.name,
                    description = request.description,
                    placementIds = request.placementIds.toMutableList(),
                )
            )
            .toDTO()
    }

    fun listZones(pageStart: IdType?, pageSize: Int): Pair<List<ZoneDTO>, PageDTO> {
        val all = zoneRepository.findAll().sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun updateZone(id: IdType, request: UpdateZoneRequestDTO): ZoneDTO {
        val zone = getZone(id)
        request.name?.let { zone.name = it }
        request.description?.let { zone.description = it }
        request.placementIds?.let {
            validatePlacements(it)
            zone.placementIds = it.toMutableList()
        }
        request.status?.let { zone.status = it.toEntity() }
        return zoneRepository.save(zone).toDTO()
    }

    fun deleteZone(id: IdType) {
        zoneRepository.delete(getZone(id))
    }
}
