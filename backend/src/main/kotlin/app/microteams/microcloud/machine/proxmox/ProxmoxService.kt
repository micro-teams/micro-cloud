/*
 *  Description: Super-admin management of Proxmox clusters: create / list / get / update / delete,
 *               plus a live inventory read. The token secret is accepted on write and used only at
 *               runtime; it is never returned. Deletion is refused while placements still reference
 *               the cluster (guard added once placements exist).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.proxmox

import app.microteams.microcloud.common.helper.PageHelper
import app.microteams.microcloud.model.*
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Maps to a DTO. The token secret is deliberately absent from the DTO — write-only. */
fun ProxmoxCluster.toDTO() =
    ProxmoxClusterDTO(
        id = this.id!!,
        name = this.name!!,
        apiUrl = this.apiUrl!!,
        tokenId = this.tokenId!!,
        verifyTls = this.verifyTls,
        status =
            when (this.status) {
                ProxmoxClusterStatus.ACTIVE -> ProxmoxClusterStatusDTO.active
                ProxmoxClusterStatus.DISABLED -> ProxmoxClusterStatusDTO.disabled
            },
        createdAt = this.createdAt?.atOffset(java.time.ZoneOffset.UTC),
    )

fun ProxmoxClusterStatusDTO.toEntity() =
    when (this) {
        ProxmoxClusterStatusDTO.active -> ProxmoxClusterStatus.ACTIVE
        ProxmoxClusterStatusDTO.disabled -> ProxmoxClusterStatus.DISABLED
    }

@Service
@Transactional
class ProxmoxService(
    private val clusterRepository: ProxmoxClusterRepository,
    private val proxmoxClient: ProxmoxClient,
) {
    fun getCluster(id: IdType): ProxmoxCluster =
        clusterRepository.findById(id).orElseThrow { NotFoundError("proxmox-cluster", id) }

    fun getClusterDTO(id: IdType): ProxmoxClusterDTO = getCluster(id).toDTO()

    fun createCluster(request: CreateProxmoxClusterRequestDTO): ProxmoxClusterDTO =
        clusterRepository
            .save(
                ProxmoxCluster(
                    name = request.name,
                    apiUrl = request.apiUrl,
                    tokenId = request.tokenId,
                    tokenSecret = request.tokenSecret,
                    verifyTls = request.verifyTls ?: true,
                )
            )
            .toDTO()

    fun listClusters(pageStart: IdType?, pageSize: Int): Pair<List<ProxmoxClusterDTO>, PageDTO> {
        val all = clusterRepository.findAll().sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun updateCluster(id: IdType, request: UpdateProxmoxClusterRequestDTO): ProxmoxClusterDTO {
        val cluster = getCluster(id)
        request.name?.let { cluster.name = it }
        request.apiUrl?.let { cluster.apiUrl = it }
        request.tokenId?.let { cluster.tokenId = it }
        request.tokenSecret?.let { cluster.tokenSecret = it }
        request.verifyTls?.let { cluster.verifyTls = it }
        request.status?.let { cluster.status = it.toEntity() }
        return clusterRepository.save(cluster).toDTO()
    }

    fun deleteCluster(id: IdType) {
        val cluster = getCluster(id)
        // TODO: refuse deletion while placements reference this cluster (once placements exist).
        clusterRepository.delete(cluster)
    }

    fun readInventory(id: IdType): ProxmoxInventoryDTO {
        val inventory = proxmoxClient.readInventory(getCluster(id))
        return ProxmoxInventoryDTO(
            nodes = inventory.nodes,
            pools = inventory.pools,
            storages =
                inventory.storages.map {
                    ProxmoxInventoryStoragesInnerDTO(
                        node = it.node,
                        storage = it.storage,
                        content = it.content,
                    )
                },
            bridges =
                inventory.bridges.map {
                    ProxmoxInventoryBridgesInnerDTO(
                        node = it.node,
                        bridge = it.bridge,
                        cidr = it.cidr,
                    )
                },
        )
    }
}
