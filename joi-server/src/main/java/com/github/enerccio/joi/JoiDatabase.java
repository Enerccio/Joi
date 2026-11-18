package com.github.enerccio.joi;

import com.github.enerccio.marginalia.DatabaseCheck;

/**
 * Joi's database: its Flyway history ({@code classpath:migration}, the platform's tables included) and the tables every
 * Joi database has, which the start, a restore and the backup before a migration check. Set on the
 * {@code configuration} bean ({@code databaseSchema}).
 */
public final class JoiDatabase {

    /**
     * The platform's core tables for now; Joi's own tables join them once it has some.
     */
    public static final DatabaseCheck.Schema SCHEMA = new DatabaseCheck.Schema("classpath:migration",
            DatabaseCheck.Schema.PLATFORM.coreTables());

    private JoiDatabase() {
    }
}
