/*
 *  Description: Upserts one catalog template row in its OWN transaction. Split out from
 *               TemplateService so directory-scan failures are isolated: each template is written in
 *               a fresh (REQUIRES_NEW) transaction, so if one row fails (e.g. a stale name-only
 *               unique constraint on a not-yet-migrated database) it rolls back alone — it neither
 *               poisons the caller's transaction nor crashes @PostConstruct startup.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.template

import app.microteams.microcloud.machine.MachineKind
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class TemplateCatalogWriter(private val templateRepository: MachineTemplateRepository) {

    /** Upsert the (name, kind) template with its source, in an isolated transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun upsert(name: String, kind: MachineKind, source: String) {
        val template =
            templateRepository.findByNameAndKind(name, kind).orElseGet {
                MachineTemplate(name = name, kind = kind)
            }
        template.kind = kind
        template.source = source
        templateRepository.save(template)
    }
}
