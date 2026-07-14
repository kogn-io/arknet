package de.hauschel.arknet.ul.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.application.port.in.AddTerm;
import de.hauschel.arknet.ul.application.port.in.GetTerm;
import de.hauschel.arknet.ul.application.port.in.ListTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Application service implementing the glossary-term use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link TermRepository}
 * driven port. The component is wired as a plain object (constructor injection) by
 * the composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity is assigned as {@code TERM-N}, where {@code N}
 * is one above the highest running number currently used in the target workspace
 * (numbering is independent per workspace). The identity is deliberately independent
 * of the term's label, so relabeling never changes identity (a core SKOS principle).</p>
 */
public class TermService implements AddTerm, ListTerms, GetTerm {

    private static final String ID_PREFIX = "TERM";

    private final TermRepository repository;

    /**
     * Creates the service.
     *
     * @param repository the driven persistence port (must not be {@code null})
     */
    public TermService(TermRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Term add(WorkspaceId workspaceId, NewTerm command) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(command, "command");
        TermId id = nextId(workspaceId);
        Term term = new Term(id, command.prefLabel(), command.definition(), command.actorFacet());
        repository.save(workspaceId, term);
        return term;
    }

    @Override
    public List<Term> list(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return repository.findAll(workspaceId);
    }

    @Override
    public Optional<Term> get(WorkspaceId workspaceId, TermId id) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(id, "id");
        return repository.findById(workspaceId, id);
    }

    /**
     * Derives the next free identity in {@code workspaceId}: the highest running
     * number currently in use, plus one (starting at 1).
     */
    private TermId nextId(WorkspaceId workspaceId) {
        int next = repository.findAll(workspaceId).stream()
                .mapToInt(t -> runningNumber(t.id()))
                .max()
                .orElse(0) + 1;
        return new TermId(ID_PREFIX + "-" + next);
    }

    /** Parses the running number from an id such as {@code TERM-7} (0 if not parseable). */
    private static int runningNumber(TermId id) {
        String value = id.value();
        int dash = value.lastIndexOf('-');
        if (dash < 0 || dash == value.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
