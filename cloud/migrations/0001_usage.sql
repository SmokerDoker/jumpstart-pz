-- Voluntary reports of unverified Java mod jars (see README.md, "What is stored").

-- one accepted report per installation and day
CREATE TABLE reports (
    day          TEXT NOT NULL,      -- YYYY-MM-DD (UTC)
    install_hash TEXT NOT NULL,      -- SHA-256(REPORT_SALT + installation id), hex
    client       TEXT NOT NULL,      -- e.g. "jumpstart/0.2"
    PRIMARY KEY (day, install_hash)
);

-- which installation uses which unverified jar, last seen when
CREATE TABLE sightings (
    sha256       TEXT NOT NULL,      -- jar SHA-256, hex
    install_hash TEXT NOT NULL,
    last_day     TEXT NOT NULL,
    workshop_id  TEXT,               -- Steam Workshop item, if the jar came from there
    mod_id       TEXT,               -- id from mod.info
    size         INTEGER,
    PRIMARY KEY (sha256, install_hash)
);
CREATE INDEX sightings_by_day ON sightings (last_day);
