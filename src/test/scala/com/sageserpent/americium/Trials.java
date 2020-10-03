package com.sageserpent.americium;

import scala.NotImplementedError;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public abstract class Trials<Case> {
    abstract <TransformedCase> Trials<TransformedCase> map(Function<Case, TransformedCase> transform);

    abstract <TransformedCase> Trials<TransformedCase> flatMap(Function<Case, Trials<TransformedCase>> step);

    abstract Trials<Case> filter(Predicate<Case> predicate);

    abstract static class TrialException extends RuntimeException {
        /**
         * @return The case that provoked the exception.
         */
        abstract Object provokingCase();


        /**
         * @return A recipe that can be used to reproduce the provoking case
         * when supplied to the corresponding trials instance.
         */
        abstract String recipe();
    }

    /**
     * Consume trial cases until either there are no more or an exception is thrown by {@code consumer}.
     * If an exception is thrown, attempts will be made to shrink the trial case that caused the
     * exception to a simpler case that throws an exception - the specific kind of exception isn't
     * necessarily the same between the first exceptional case and the final simplified one. The exception
     * from the simplified case (or the original exceptional case if it could not be simplified) is wrapped
     * in an instance of {@link TrialException} which also contains the case that provoked the exception.
     *
     * @param consumer An operation that consumes a 'Case', and may throw an exception.
     */
    abstract void supplyTo(Consumer<Case> consumer);

    /**
     * Reproduce a specific case in a repeatable fashion, based on a recipe.
     *
     * @param recipe This encodes a specific case and will only be understood by the
     *               same *value* of trials instance that was used to obtain it.
     * @return The specific case denoted by the recipe.
     *
     * @throws RuntimeException if the recipe is not one corresponding to the receiver,
     * either due to it being created by a different flavour of trials instance.
     */
    abstract Case reproduce(String recipe);


    public static <Case> Trials<Case> constant(Case value) {
        throw new NotImplementedError();
    }

    public static <Case> Trials<Case> choose(Case... choices) {
        throw new NotImplementedError();
    }

    public static <Case> Trials<Case> choose(Iterable<Case> choices) {
        throw new NotImplementedError();
    }

    public static <Case> Trials<Case> alternate(Trials<Case>... alternatives) {
        throw new NotImplementedError();
    }

    public static <Case> Trials<Case> alternate(Iterable<Trials<Case>> alternatives) {
        throw new NotImplementedError();
    }
}