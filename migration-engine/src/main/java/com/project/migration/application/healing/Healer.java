package com.project.migration.application.healing;

/**
 * One self-healing attempt as the stability gate sees it.
 */
public interface Healer {

    HealingResult heal(HealingRequest request);
}
