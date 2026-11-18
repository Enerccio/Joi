#!/usr/bin/env python3
"""Brings a dev database up to the current V1__platform.sql without losing its data.

While V1 stays open (CLAUDE.md), schema changes are edited into V1 in place, so a dev database made from an older V1
fails Flyway's checksum at start. This tool rebuilds the database from the current V1 and copies the data over:

1. a copy of the database goes to <data>/db-backups/joi-before-v1-upgrade-<time>.sqlite;
2. a new database is made from the current V1 (every table, index and sequence row);
3. the rows of every table that is in both are copied, by the columns they share (a new table stays empty, a new
   column gets its default);
4. Flyway's history is copied and the checksum of V1 is set to the one of the current file;
5. the new database takes the place of the old one.

It refuses when the data would not fit: a table or column of the old database with data that is gone from V1, or a
new NOT NULL column without a default in a table with rows (--force drops the first kind). Stop the server first.

    python3 tools/upgrade_dev_db.py                 # ~/.joi/joi.sqlite, or $JOI_DATA/joi.sqlite
    python3 tools/upgrade_dev_db.py --db PATH --dry-run
"""

import argparse
import io
import os
import sqlite3
import sys
import time
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIGRATION = ROOT / "joi-server" / "src" / "main" / "resources" / "migration" / "V1__platform.sql"
HISTORY = "flyway_schema_history"
# Hibernate's temporary tables of bulk updates: it creates them itself, they hold nothing between statements
HIBERNATE_TEMP = ("HT_", "HTE_")


def flyway_checksum(text):
    """Flyway's checksum of a migration: CRC32 of its lines without line breaks (and without a BOM), a signed int."""
    crc = 0
    first = True
    for line in io.StringIO(text, newline=None):
        line = line.rstrip("\r\n")
        if first:
            line = line.lstrip("﻿")
            first = False
        crc = zlib.crc32(line.encode("utf-8"), crc)
    return crc - (1 << 32) if crc >= (1 << 31) else crc


def tables(db, schema="main"):
    rows = db.execute(f"SELECT name, sql FROM {schema}.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
    return {name: sql for name, sql in rows}


def columns(db, table, schema="main"):
    """name -> (notnull, default)"""
    return {row[1]: (row[3], row[4]) for row in db.execute(f'PRAGMA {schema}.table_info("{table}")')}


def count(db, table, schema="main", where=""):
    return db.execute(f'SELECT COUNT(*) FROM {schema}."{table}" {where}').fetchone()[0]


def default_db():
    data = os.environ.get("JOI_DATA")
    return Path(data) / "joi.sqlite" if data else Path.home() / ".joi" / "joi.sqlite"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", type=Path, default=default_db(), help="the database (default: %(default)s)")
    parser.add_argument("--migration", type=Path, default=MIGRATION, help="the V1 to upgrade to")
    parser.add_argument("--dry-run", action="store_true", help="check and report, change nothing")
    parser.add_argument("--force", action="store_true", help="drop the data of tables and columns gone from V1")
    args = parser.parse_args()

    if not args.db.exists():
        sys.exit(f"No database at {args.db}: the server creates it at its first start.")
    script = args.migration.read_text(encoding="utf-8")
    checksum = flyway_checksum(script)

    old = sqlite3.connect(args.db)
    history = old.execute(f"SELECT version, checksum FROM {HISTORY} ORDER BY installed_rank").fetchall()
    if [v for v, _ in history] != ["1"]:
        sys.exit(f"Expected V1 only in the history, found {[v for v, _ in history]}: V1 is closed, use migrations.")
    if history[0][1] == checksum:
        print(f"{args.db} is already at the current V1 (checksum {checksum}).")
        return
    try:
        # nobody may write while the data is copied: a running server holds the database
        old.execute("BEGIN EXCLUSIVE")
        old.execute("ROLLBACK")
    except sqlite3.OperationalError:
        sys.exit("The database is in use: stop the server first.")
    old.close()

    work = args.db.with_name(args.db.stem + ".upgrade.sqlite")
    work.unlink(missing_ok=True)
    new = sqlite3.connect(work)
    new.executescript(script)
    new.execute("PRAGMA foreign_keys = OFF")
    new.execute("ATTACH DATABASE ? AS old", (str(args.db),))

    old_tables = tables(new, "old")
    new_tables = tables(new)
    problems, dropped, report = [], [], []

    for table in sorted(set(old_tables) - set(new_tables) - {HISTORY}):
        if table.startswith(HIBERNATE_TEMP):
            new.execute(old_tables[table])  # empty, Hibernate's own
            continue
        rows = count(new, table, "old")
        (dropped if rows else report).append(f"table {table} is gone from V1 ({rows} rows)")

    for table in sorted(set(new_tables) & set(old_tables)):
        old_columns, new_columns = columns(new, table, "old"), columns(new, table)
        shared = [c for c in new_columns if c in old_columns]
        rows = count(new, table, "old")
        for column in old_columns:
            if column not in new_columns:
                filled = count(new, table, "old", f'WHERE "{column}" IS NOT NULL')
                (dropped if filled else report).append(f"column {table}.{column} is gone from V1 ({filled} values)")
        for column, (notnull, default) in new_columns.items():
            if column not in old_columns and notnull and default is None and rows:
                problems.append(f"new column {table}.{column} is NOT NULL without a default and {table} has {rows} rows")
        if not problems:
            names = ", ".join(f'"{c}"' for c in shared)
            new.execute(f'DELETE FROM main."{table}"')
            new.execute(f'INSERT INTO main."{table}" ({names}) SELECT {names} FROM old."{table}"')
            report.append(f"{table}: {rows} rows")

    for table in sorted(set(new_tables) - set(old_tables)):
        report.append(f"{table}: new")

    new.execute(old_tables[HISTORY])
    new.execute(f"INSERT INTO main.{HISTORY} SELECT * FROM old.{HISTORY}")
    new.execute(f"UPDATE main.{HISTORY} SET checksum = ? WHERE version = '1'", (checksum,))

    print("\n".join(report))
    if problems:
        new.close()
        work.unlink()
        sys.exit("Can't upgrade:\n  " + "\n  ".join(problems))
    if dropped and not args.force:
        new.close()
        work.unlink()
        sys.exit("Data would be lost (--force drops it):\n  " + "\n  ".join(dropped))
    for line in dropped:
        print(f"dropped: {line}")

    new.commit()
    new.execute("DETACH DATABASE old")
    result = new.execute("PRAGMA integrity_check").fetchone()[0]
    new.close()
    if result != "ok":
        work.unlink()
        sys.exit(f"The new database is not consistent: {result}")
    if args.dry_run:
        work.unlink()
        print("Dry run: nothing changed.")
        return

    backups = args.db.parent / "db-backups"
    backups.mkdir(exist_ok=True)
    backup = backups / f"joi-before-v1-upgrade-{time.strftime('%Y%m%d-%H%M%S')}.sqlite"
    # through SQLite, so what is still in the write-ahead log is in the backup too
    with sqlite3.connect(args.db) as source, sqlite3.connect(backup) as target:
        source.backup(target)
    for suffix in ("-wal", "-shm", "-journal"):
        Path(str(args.db) + suffix).unlink(missing_ok=True)
    os.replace(work, args.db)
    print(f"Upgraded {args.db} to the current V1 (checksum {checksum}); the old one is {backup}.")


if __name__ == "__main__":
    main()
