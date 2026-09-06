// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.actor.application.port.in.ResolveRoles;
import de.hauschel.arknet.actor.domain.DuplicateRoleCodeException;
import de.hauschel.arknet.actor.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.actor.domain.Role;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.RoleDisplayFallback;
import de.hauschel.arknet.actor.domain.RoleNotFoundException;
import de.hauschel.arknet.actor.domain.RoleReferencedException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Driven port: persistence capability the role resource type needs from the outside - mirrors
 * {@code ConstraintRepository}'s multilingual write/read shape (own {@link #create}/
 * {@link #compareAndUpdate} language arguments, {@link #findByCode}/{@link #findAll}
 * {@code displayLocale}, {@link #findAllDisplayFallback}), not {@link ActorRepository}'s untagged
 * one - see {@link Role}'s own javadoc for why.
 */
public interface RoleRepository {

    /**
     * Persists a brand-new role whose identity does not yet exist in the project.
     *
     * @param projectId the project (architecture model) to store the role in
     * @param role      the role to create
     * @param language  the BCP-47 language tag {@code role.name()} and, if present,
     *                  {@code role.description()} are written in, or {@code null} for plain,
     *                  untagged literals - the same tag applies to both, since a freshly created
     *                  role is written whole in one call
     * @throws ResourceAlreadyExistsException if a role with this identity already exists
     * @throws DuplicateRoleCodeException     if another role already carries this role's
     *                                        {@link RoleCode}
     * @throws RuntimeException if {@code role} violates a SHACL write constraint - see
     *                          {@link ActorRepository#create} for why the concrete type is not
     *                          fixed by this port
     */
    void create(ProjectId projectId, Role role, String language);

    /**
     * Replaces an existing role by identity, but only if its current concurrency token still
     * equals {@code expectedHead} - the compare-and-set guard against the lost-update race, mirrors
     * {@code ConstraintRepository#compareAndUpdate} exactly, including its two independent
     * per-field language arguments.
     *
     * @param projectId          the project (architecture model) the role lives in
     * @param expectedHead       the {@link RevisionToken} the caller last observed for this role
     *                           (from {@link #findCurrentByCode}), or {@code null} if the caller
     *                           expects no revision to exist yet
     * @param updated            the role to store in place of the current one, if its head still
     *                           matches {@code expectedHead}
     * @param nameLanguage       the BCP-47 language tag {@code updated.name()} is written in for
     *                           this call, or {@code null} for a plain, untagged literal. A call
     *                           that leaves the name's content unchanged must pass through the tag
     *                           the value it read was itself resolved under (see
     *                           {@link CurrentRole#nameLanguage()}), so the write is a scoped no-op
     *                           on that one language variant; every other language-tagged variant
     *                           of {@code name} survives untouched
     * @param descriptionLanguage the same as {@code nameLanguage}, for {@code updated.description()}
     *                           (see {@link CurrentRole#descriptionLanguage()} for the pass-through
     *                           case) - independent of {@code nameLanguage}
     * @param defaultLanguage    the target project's configured default language, or {@code null}
     *                           if it has none - used only to decide whether an existing
     *                           <em>untagged</em> literal on {@code name}/{@code description}
     *                           should be swept away rather than preserved (issue #258's lazy sweep,
     *                           mirroring {@code ConstraintRepository#compareAndUpdate} exactly)
     * @throws RoleNotFoundException              if no role with this identity exists at all
     * @throws RoleConcurrentlyModifiedException  if {@code expectedHead} no longer matches the
     *                                            stored role's current head
     * @throws DuplicateRoleCodeException         if {@code updated.code()} already labels a
     *                                            different role in the project
     * @throws RuntimeException if {@code updated} violates a SHACL write constraint
     */
    void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Role updated,
            String nameLanguage, String descriptionLanguage, String defaultLanguage);

    /**
     * Finds a role by its human-readable business code within a project.
     *
     * @param projectId     the project (architecture model) to look up the role in
     * @param code          the role code (e.g. {@code ROLE-1})
     * @param displayLocale the BCP-47 language tag the caller wants {@code name}/
     *                      {@code description} shown in, overriding this repository's own
     *                      configured display-language preference for this one call, or
     *                      {@code null} to use that preference unchanged
     * @return the role if present, otherwise {@link Optional#empty()}
     */
    Optional<Role> findByCode(ProjectId projectId, RoleCode code, String displayLocale);

    /**
     * Reads a role's current state together with its concurrency token - mirrors
     * {@code ConstraintRepository#findCurrentByCode} exactly, including which language variant
     * this read projects through (the target project's own configured default, not the reading
     * process's own preference - issue #456).
     *
     * @param projectId       the project (architecture model) to look up the role in
     * @param code            the role code (e.g. {@code ROLE-1})
     * @param defaultLanguage the project's configured default language, or {@code null} if it has
     *                        none - the BCP-47 tag {@code name}/{@code description} are selected
     *                        under, degrading along the usual fallback chain
     * @return the role and its current head, or {@link Optional#empty()} if no role with this code
     *         exists
     */
    Optional<CurrentRole> findCurrentByCode(ProjectId projectId, RoleCode code, String defaultLanguage);

    /**
     * A role's state paired with its current concurrency token (the {@link RevisionToken}, or
     * {@code null} if no write has ever been recorded for this role), as read together by
     * {@link #findCurrentByCode}.
     *
     * @param value              the role as currently read
     * @param head               the concurrency token, or {@code null}
     * @param nameLanguage       the BCP-47 language tag of the specific {@code name} literal
     *                           {@code value.name()} was selected from (or {@code null} if that
     *                           literal is untagged) - a read-modify-write round trip that does not
     *                           itself intend to change {@code name} must pass this straight
     *                           through to {@link #compareAndUpdate}'s {@code nameLanguage}
     * @param descriptionLanguage the same as {@code nameLanguage}, for {@code value.description()}
     */
    record CurrentRole(Role value, RevisionToken head, String nameLanguage, String descriptionLanguage) {
    }

    /**
     * Returns all roles stored in a project.
     *
     * @param projectId     the project (architecture model) to list roles from
     * @param displayLocale the BCP-47 language tag the caller wants each role's {@code name}/
     *                      {@code description} shown in, overriding this repository's own
     *                      configured display-language preference for this one call, or
     *                      {@code null} to use that preference unchanged
     * @return all roles, never {@code null}
     */
    List<Role> findAll(ProjectId projectId, String displayLocale);

    /**
     * Companion to {@link #findAll}: not the displayed value, but whether displaying it required
     * falling back past the requested/project-default language tier (kogn-io/arknet#475) - mirrors
     * {@code ConstraintRepository#findAllDisplayFallback} exactly.
     *
     * @param projectId     the project (architecture model) to list roles from
     * @param displayLocale the same override {@link #findAll} accepts
     * @return see
     *         {@link de.hauschel.arknet.actor.application.port.in.DescribeRoleDisplayFallback#describe}
     */
    Map<RoleCode, RoleDisplayFallback> findAllDisplayFallback(ProjectId projectId, String displayLocale);

    /**
     * Returns the business code of every role registered in a project, read independently of
     * whether that role can currently be materialised into a {@link Role} - mirrors
     * {@code ConstraintRepository#findAllCodes} exactly, including the kogn-io/arknet#360 reasoning
     * it exists for: {@code RoleService#add} derives the next free {@code ROLE-N} from this, not
     * from {@link #findAll}, so a store-first role written without a name still holds its number.
     *
     * @param projectId the project (architecture model) to read codes from
     * @return every registered role's business code, never {@code null}
     */
    List<RoleCode> findAllCodes(ProjectId projectId);

    /**
     * Deletes the role identified by {@code code}, and every triple it carries in this hexagon's
     * own named graph, from the project. Rejects outright, without deleting anything, if anything
     * else in the project still references the role - see {@link RoleReferencedException}.
     *
     * @param projectId the project (architecture model) the role lives in
     * @param code      the role code, e.g. {@code ROLE-1}
     * @throws RoleNotFoundException   if no role with this identity exists
     * @throws RoleReferencedException if anything else in the project still references the role
     */
    void delete(ProjectId projectId, RoleCode code);

    /**
     * Returns the business codes of roles that were deleted from the project and are kept out of
     * circulation - mirrors {@link ActorRepository#findRetainedCodes} exactly (issue #350's
     * mechanism, reused here).
     *
     * @param projectId the project (architecture model) to read the retained codes of
     * @return the retained codes, never {@code null}
     */
    List<RoleCode> findRetainedCodes(ProjectId projectId);

    /**
     * Finds every role in a project whose identity is among {@code ids}, in one store round-trip
     * - backs {@link ResolveRoles}, the same batch shape {@code ActorRepository#findByIds}
     * establishes for the sibling resource type. Not a per-id existence check: an id absent from
     * the project (or not a role at all) is simply absent from the result, never an error.
     *
     * <p>Returns the slim {@link ResolveRoles.ResolvedRole} projection, not the full {@link Role}
     * aggregate: the only consumer of this method is {@link ResolveRoles}, which exists purely to
     * answer "what code and name identify this identity" for display. Unlike
     * {@code ActorRepository#findByIds}'s {@code ActorProjection}, the projection here also
     * carries the resolved {@code name} - a role's name is language-tagged, so {@code displayLocale}
     * selects which variant a caller sees, the same fallback chain {@link #findByCode} already
     * applies.</p>
     *
     * @param projectId     the project (architecture model) to look up roles in
     * @param displayLocale the BCP-47 language tag the caller wants each role's resolved
     *                      {@code name} shown in, overriding this repository's own configured
     *                      display-language preference for this one call, or {@code null} to use
     *                      that preference unchanged
     * @param ids           the opaque identities to resolve; an empty list yields an empty result
     * @return the resolved roles found, in no particular order, never {@code null}
     */
    List<ResolveRoles.ResolvedRole> findByIds(ProjectId projectId, String displayLocale, List<ResourceId> ids);
}
