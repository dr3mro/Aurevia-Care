package com.aurevia.exchange.task;

import java.util.List;

/**
 * Current XCH-02 state together with its immutable transition history.
 *
 * @return this record
 */
public record ExchangeTaskView(ExchangeTask task, List<ExchangeTaskEvent> history) {

    /**
     * Copy the caller's history so the view cannot be changed through it.
     *
     * @throws NullPointerException when history is null
     */
    public ExchangeTaskView {
        history = List.copyOf(history);
    }

    /**
     * Return the immutable event history.
     *
     * @return history events oldest first
     */
    @Override
    public List<ExchangeTaskEvent> history() {
        return List.copyOf(history);
    }
}
