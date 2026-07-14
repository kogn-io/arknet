package de.hauschel.arknet.uc.domain;

import java.util.Objects;

/**
 * Reference to an actor participating in a {@link UseCase}, carried as a bare
 * business label such as {@code Customer}.
 *
 * <p><strong>Deliberately not a link to the ubiquitous-language bounded
 * context.</strong> Actors are modelled there (as a facet on glossary terms);
 * the use-cases component must not depend on that BC. This value object therefore
 * holds only the actor's label as a string. Resolving the label to an actual
 * actor - and rejecting unknown labels - is the job of a driven adapter
 * (lookup-by-label against the store), not of this pure domain type.</p>
 *
 * @param label the non-blank actor label (e.g. {@code Customer})
 */
public record ActorRef(String label) {

    public ActorRef {
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("ActorRef label must not be blank");
        }
    }
}
