// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrConcurrentlyModifiedException;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.DuplicateAdrCodeException;
import de.hauschel.arknet.adr.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * In-memory test double for {@link AdrRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores decisions, keyed by project then opaque
 * identity, so the service's policy can be exercised end-to-end. Insertion order is preserved to
 * make {@link #findAll(ProjectId)} assertions deterministic. {@link #create} mirrors the real
 * out-adapter's in-transaction guards: an identity collision rejects with
 * {@link ResourceAlreadyExistsException}, a business-code collision with
 * {@link DuplicateAdrCodeException}.</p>
 *
 * <p><strong>Concurrency token.</strong> Mirrors the real
 * {@code de.hauschel.arknet.persistence.WriteFunnel}'s head, minimally: a fresh opaque marker minted
 * on every {@link #create}/{@link #compareAndUpdate}, tracked per identity - {@link #findCurrentByCode}
 * hands it out alongside the decision, {@link #compareAndUpdate} rejects a stale one, exactly the
 * CAS contract the real adapter enforces via {@code arkprov:head}.</p>
 */
class InMemoryAdrRepository implements AdrRepository {

    /**
     * Orders {@code ADR-N} code strings by their parsed running number, not by {@link String}'s
     * natural (lexicographic) order - {@code "ADR-10"} sorts before {@code "ADR-2"} under natural
     * order once a project passes ten decisions. Falls back to natural string order when the running
     * number ties, which every well-formed {@code ADR-N} code only ever does with itself. Mirrors
     * {@code AdrService}'s and {@code KognioRdfAdrRepository}'s identically-named, identically-behaved
     * helper (this fake has no dependency it could reuse it through).
     */
    private static final Comparator<String> CODE_BY_RUNNING_NUMBER =
            Comparator.<String>comparingInt(InMemoryAdrRepository::runningNumber)
                    .thenComparing(Comparator.naturalOrder());

    private final Map<ProjectId, Map<AdrId, Adr>> byProject = new LinkedHashMap<>();
    private final Map<AdrId, String> headByIdentity = new LinkedHashMap<>();
    private final Map<ProjectId, List<AdrCode>> retainedByProject = new LinkedHashMap<>();

    /**
     * Simplified language bookkeeping (kogn-io/arknet#357): unlike the real out-adapter, this fake
     * never actually stores separate RDF literals per language - it only tracks which tags a caller
     * has written for {@code name}/{@code context}/{@code decision} (accumulated across writes, the
     * same "preserved forever unless overwritten" behaviour the real preservation mechanism gives),
     * and the tag currently associated with each field/position - just enough for
     * {@code AdrServiceTest} to exercise the service's touched/pass-through and
     * new-language-variant policy without a real triple store.
     */
    private final Map<AdrId, Set<String>> nameContextDecisionLanguagesByIdentity = new LinkedHashMap<>();
    private final Map<AdrId, String> nameLanguageByIdentity = new LinkedHashMap<>();
    private final Map<AdrId, String> contextLanguageByIdentity = new LinkedHashMap<>();
    private final Map<AdrId, String> decisionLanguageByIdentity = new LinkedHashMap<>();
    private final Map<AdrId, Map<Integer, String>> consequenceLanguageByIdentity = new LinkedHashMap<>();
    private final Map<AdrId, Map<Integer, String>> optionLanguageByIdentity = new LinkedHashMap<>();

    /**
     * Store-first (pre-#357) {@code arkarch:supersedes} pairs, seeded directly by
     * {@link #seedLegacySupersession} rather than reachable through any {@link AdrService} in-port -
     * exactly as a real project's legacy data would be, having been written before this issue existed
     * rather than through {@code adr_supersede}.
     */
    private final Map<ProjectId, List<LegacySupersession>> legacyByProject = new LinkedHashMap<>();

    /**
     * Identity-to-code pairs seeded by {@link #seedUnmaterialisableCode} - deliberately absent from
     * {@link #byProject}, so {@link #findAll} never sees them, while {@link #findAllCodes} and
     * {@link #findCodesByIds} both do. What lets a test simulate the real out-adapter's store-first
     * (ADR-005) read-time skip without a real store: a decision this hexagon considers alive, whose
     * code and identity are both assigned, but that cannot be materialised into an {@link Adr} right
     * now (kogn-io/arknet#359).
     */
    private final Map<ProjectId, Map<AdrId, AdrCode>> unmaterialisableByProject = new LinkedHashMap<>();

    @Override
    public void create(ProjectId projectId, Adr adr, String language) {
        Map<AdrId, Adr> adrs = byProject.computeIfAbsent(projectId, k -> new LinkedHashMap<>());
        if (adrs.containsKey(adr.id())) {
            throw new ResourceAlreadyExistsException(projectId, adr.id().value());
        }
        if (adrs.values().stream().anyMatch(existing -> existing.code().equals(adr.code()))) {
            throw new DuplicateAdrCodeException(projectId, adr.code());
        }
        adrs.put(adr.id(), adr);
        headByIdentity.put(adr.id(), UUID.randomUUID().toString());
        if (language != null) {
            nameContextDecisionLanguagesByIdentity.put(adr.id(), new LinkedHashSet<>(Set.of(language)));
        }
        nameLanguageByIdentity.put(adr.id(), language);
        contextLanguageByIdentity.put(adr.id(), language);
        decisionLanguageByIdentity.put(adr.id(), language);
        Map<Integer, String> consequenceTags = new LinkedHashMap<>();
        adr.consequences().forEach(c -> consequenceTags.put(c.position(), language));
        consequenceLanguageByIdentity.put(adr.id(), consequenceTags);
        Map<Integer, String> optionTags = new LinkedHashMap<>();
        adr.consideredOptions().forEach(o -> optionTags.put(o.position(), language));
        optionLanguageByIdentity.put(adr.id(), optionTags);
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, String expectedHead, Adr updated,
            String nameLanguage, String contextLanguage, String decisionLanguage,
            Map<Integer, String> consequenceLanguageByPosition, Map<Integer, String> optionLanguageByPosition,
            String defaultLanguage) {
        Map<AdrId, Adr> adrs = byProject.getOrDefault(projectId, Map.of());
        if (!adrs.containsKey(updated.id())) {
            throw new AdrNotFoundException(projectId, updated.code());
        }
        if (!Objects.equals(headByIdentity.get(updated.id()), expectedHead)) {
            throw new AdrConcurrentlyModifiedException(projectId, updated.code());
        }
        adrs.put(updated.id(), updated);
        headByIdentity.put(updated.id(), UUID.randomUUID().toString());
        Set<String> languages = nameContextDecisionLanguagesByIdentity
                .computeIfAbsent(updated.id(), key -> new LinkedHashSet<>());
        if (nameLanguage != null) {
            languages.add(nameLanguage);
        }
        if (contextLanguage != null) {
            languages.add(contextLanguage);
        }
        if (decisionLanguage != null) {
            languages.add(decisionLanguage);
        }
        nameLanguageByIdentity.put(updated.id(), nameLanguage);
        contextLanguageByIdentity.put(updated.id(), contextLanguage);
        decisionLanguageByIdentity.put(updated.id(), decisionLanguage);
        consequenceLanguageByIdentity.put(updated.id(), new LinkedHashMap<>(consequenceLanguageByPosition));
        optionLanguageByIdentity.put(updated.id(), new LinkedHashMap<>(optionLanguageByPosition));
    }

    /**
     * Mirrors the real out-adapter's delete in the two respects the service's policy depends on: the
     * decision goes away, and its business code is retained (see {@link #findRetainedCodes}) so
     * {@code adr_add} cannot hand the number out again.
     *
     * <p>Deliberately <em>without</em> the adapter's own in-transaction reference check: this fake
     * has no transaction, and the check it would duplicate is the race-free backstop pinned against
     * the real store in {@code KognioRdfAdrRepositoryTest}. The service's own didactic check runs
     * before this method is ever reached, which is what {@code AdrServiceTest} exercises.</p>
     */
    @Override
    public void delete(ProjectId projectId, AdrCode code) {
        Map<AdrId, Adr> adrs = byProject.getOrDefault(projectId, Map.of());
        Adr stored = adrs.values().stream()
                .filter(adr -> adr.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AdrNotFoundException(projectId, code));
        adrs.remove(stored.id());
        headByIdentity.remove(stored.id());
        retainedByProject.computeIfAbsent(projectId, key -> new ArrayList<>()).add(code);
    }

    @Override
    public List<AdrCode> findRetainedCodes(ProjectId projectId) {
        return List.copyOf(retainedByProject.getOrDefault(projectId, List.of()));
    }

    @Override
    public Optional<Adr> findByCode(ProjectId projectId, AdrCode code, String displayLocale) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(adr -> adr.code().equals(code))
                .findFirst();
    }

    @Override
    public Optional<CurrentAdr> findCurrentByCode(ProjectId projectId, AdrCode code) {
        return findByCode(projectId, code, null)
                .map(adr -> new CurrentAdr(adr, headByIdentity.get(adr.id()),
                        nameLanguageByIdentity.get(adr.id()), contextLanguageByIdentity.get(adr.id()),
                        decisionLanguageByIdentity.get(adr.id()),
                        nameContextDecisionLanguagesByIdentity.getOrDefault(adr.id(), Set.of()),
                        consequenceLanguageByIdentity.getOrDefault(adr.id(), Map.of()),
                        optionLanguageByIdentity.getOrDefault(adr.id(), Map.of())));
    }

    @Override
    public List<Adr> findAll(ProjectId projectId, String displayLocale) {
        return List.copyOf(byProject.getOrDefault(projectId, Map.of()).values());
    }

    /**
     * Every stored decision's code, plus every code {@link #seedUnmaterialisableCode} seeded - the
     * latter simulating what the real out-adapter's own store-first (ADR-005) read-time skip would
     * otherwise hide from {@link #findAll} alone (kogn-io/arknet#359).
     */
    @Override
    public List<AdrCode> findAllCodes(ProjectId projectId) {
        List<AdrCode> codes = new ArrayList<>(
                byProject.getOrDefault(projectId, Map.of()).values().stream().map(Adr::code).toList());
        codes.addAll(unmaterialisableByProject.getOrDefault(projectId, Map.of()).values());
        return List.copyOf(codes);
    }

    /**
     * Seeds a code {@link #findAllCodes} reports but {@link #findAll} never will, under a fresh,
     * otherwise-unused identity - simulating a decision the real out-adapter's read-time tolerance
     * skips (an unrecognised status, or a store-first status/{@code supersededBy} disagreement,
     * kogn-io/arknet#357) without needing a real store to reproduce that skip in (kogn-io/arknet#359).
     * Use {@link #seedUnmaterialisableCode(ProjectId, AdrId, AdrCode)} instead when a test needs to
     * name that identity too (e.g. as another decision's {@code supersededBy} target).
     */
    void seedUnmaterialisableCode(ProjectId projectId, AdrCode code) {
        seedUnmaterialisableCode(projectId,
                new AdrId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID())), code);
    }

    /**
     * Seeds a code (and the identity behind it) that {@link #findAllCodes} and
     * {@link #findCodesByIds} both report but {@link #findAll} never will - see
     * {@link #seedUnmaterialisableCode(ProjectId, AdrCode)} for why.
     */
    void seedUnmaterialisableCode(ProjectId projectId, AdrId id, AdrCode code) {
        unmaterialisableByProject.computeIfAbsent(projectId, key -> new LinkedHashMap<>()).put(id, code);
    }

    @Override
    public Map<AdrId, AdrCode> findCodesByIds(ProjectId projectId, Collection<AdrId> ids) {
        Map<AdrId, AdrCode> codes = new LinkedHashMap<>();
        byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(adr -> ids.contains(adr.id()))
                .forEach(adr -> codes.put(adr.id(), adr.code()));
        // Mirrors the real out-adapter: findCodesByIds resolves identity to code straight off the
        // mandatory identifier/type pair, which nothing this fake simulates as "unmaterialisable"
        // ever lacks - so an id seeded via seedUnmaterialisableCode still resolves here, exactly the
        // fallback AdrService#list depends on (kogn-io/arknet#359).
        unmaterialisableByProject.getOrDefault(projectId, Map.of()).forEach((id, code) -> {
            if (ids.contains(id)) {
                codes.putIfAbsent(id, code);
            }
        });
        return Map.copyOf(codes);
    }

    /**
     * Two sources, unioned, mirroring {@code KognioRdfAdrRepository}: {@code supersededId}'s own
     * current-model {@link Adr#supersededBy()} field, and every legacy pair whose
     * {@link LegacySupersession#supersededCode()} names it.
     */
    @Override
    public List<AdrCode> findSupersedingCodes(ProjectId projectId, AdrId supersededId) {
        TreeSet<String> codes = new TreeSet<>(CODE_BY_RUNNING_NUMBER);
        byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(adr -> supersededId.equals(adr.id()) && adr.supersededBy() != null)
                .map(adr -> codeOf(projectId, adr.supersededBy()))
                .filter(Objects::nonNull)
                .forEach(codes::add);
        String supersededCode = codeOf(projectId, supersededId);
        legacyByProject.getOrDefault(projectId, List.of()).stream()
                .filter(pair -> pair.supersededCode().value().equals(supersededCode))
                .forEach(pair -> codes.add(pair.supersedingCode().value()));
        return codes.stream().map(AdrCode::new).toList();
    }

    /**
     * The mirror of {@link #findSupersedingCodes}: a reverse read of every decision naming
     * {@code supersedingId} in its own current-model {@link Adr#supersededBy()} field, unioned with
     * every legacy pair whose {@link LegacySupersession#supersedingCode()} names it.
     */
    @Override
    public List<AdrCode> findSupersededCodes(ProjectId projectId, AdrId supersedingId) {
        TreeSet<String> codes = new TreeSet<>(CODE_BY_RUNNING_NUMBER);
        byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(adr -> supersedingId.equals(adr.supersededBy()))
                .map(adr -> adr.code().value())
                .forEach(codes::add);
        String supersedingCode = codeOf(projectId, supersedingId);
        legacyByProject.getOrDefault(projectId, List.of()).stream()
                .filter(pair -> pair.supersedingCode().value().equals(supersedingCode))
                .forEach(pair -> codes.add(pair.supersededCode().value()));
        return codes.stream().map(AdrCode::new).toList();
    }

    /**
     * The two external reverse edges only (mirrors {@code KognioRdfAdrRepository}), both with
     * {@code target} as the <em>object</em> of the edge - deliberately not a delegation to
     * {@link #findSupersededCodes}, whose legacy branch runs the opposite direction (it treats its
     * argument as the superseding decision, this method treats {@code target} as what is
     * referenced): a current-model decision naming {@code target} as its own successor, or a legacy
     * pair naming {@code target} as what it (legacy-)supersedes.
     */
    @Override
    public List<AdrCode> findSupersessionReferrers(ProjectId projectId, AdrId target) {
        TreeSet<String> codes = new TreeSet<>(CODE_BY_RUNNING_NUMBER);
        byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(adr -> target.equals(adr.supersededBy()))
                .map(adr -> adr.code().value())
                .forEach(codes::add);
        String targetCode = codeOf(projectId, target);
        legacyByProject.getOrDefault(projectId, List.of()).stream()
                .filter(pair -> pair.supersededCode().value().equals(targetCode))
                .forEach(pair -> codes.add(pair.supersedingCode().value()));
        return codes.stream().map(AdrCode::new).toList();
    }

    @Override
    public List<LegacySupersession> findLegacySupersedesEdges(ProjectId projectId) {
        return List.copyOf(legacyByProject.getOrDefault(projectId, List.of()));
    }

    /**
     * Seeds a store-first (pre-#357) {@code arkarch:supersedes} pair - what
     * {@code KognioRdfAdrRepository#findLegacySupersedesEdges} would read back from a project that
     * still carries decisions superseded before this issue. Both codes are business labels only; the
     * decisions they name need not even exist in this fake, mirroring the real read path's tolerance
     * of a dangling legacy edge.
     */
    void seedLegacySupersession(ProjectId projectId, AdrCode supersedingCode, AdrCode supersededCode) {
        legacyByProject.computeIfAbsent(projectId, key -> new ArrayList<>())
                .add(new LegacySupersession(supersedingCode, supersededCode));
    }

    /** Looks {@code id} up directly (this fake keys its inner map by identity) and returns its code. */
    private String codeOf(ProjectId projectId, AdrId id) {
        Adr adr = byProject.getOrDefault(projectId, Map.of()).get(id);
        return adr == null ? null : adr.code().value();
    }

    @Override
    public List<AdrCode> findRelatedCodes(ProjectId projectId, AdrId relatedId) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(adr -> adr.relatedTo().contains(relatedId))
                .map(adr -> adr.code().value())
                .collect(java.util.stream.Collectors.toCollection(() -> new TreeSet<>(CODE_BY_RUNNING_NUMBER)))
                .stream()
                .map(AdrCode::new)
                .toList();
    }

    /** Parses the running number from a code such as {@code ADR-7} (0 if not parseable). */
    private static int runningNumber(String code) {
        int dash = code.lastIndexOf('-');
        if (dash < 0 || dash == code.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(code.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
