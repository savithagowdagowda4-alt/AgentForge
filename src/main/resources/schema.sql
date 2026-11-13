CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(120),
    pw_hash VARCHAR(256) NOT NULL,
    salt VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description CLOB NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_mode VARCHAR(16) DEFAULT 'text',
    engine VARCHAR(32),
    checkpoints VARCHAR(200) DEFAULT '',
    location_text VARCHAR(300),
    lat DOUBLE,
    lon DOUBLE,
    location_relevant BOOLEAN DEFAULT FALSE,
    current_version INT DEFAULT 0,
    pause_requested BOOLEAN DEFAULT FALSE,
    error CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    parent_version INT,
    change_note CLOB,
    assumptions CLOB,
    plan_json CLOB,
    result_json CLOB,
    recommendation CLOB,
    confidence DOUBLE,
    status VARCHAR(32),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS findings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    agent VARCHAR(32) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(300) NOT NULL,
    detail CLOB,
    original_detail CLOB,
    confidence DOUBLE,
    status VARCHAR(32) DEFAULT 'active',
    evidence_refs VARCHAR(300),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS conflicts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    raised_by VARCHAR(32) NOT NULL,
    target_agent VARCHAR(32) NOT NULL,
    finding_id BIGINT,
    objection CLOB,
    severity VARCHAR(16),
    status VARCHAR(32) DEFAULT 'open',
    response_action VARCHAR(32),
    resolution CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    filename VARCHAR(300) NOT NULL,
    content_type VARCHAR(120),
    size_bytes BIGINT,
    stored_path VARCHAR(500),
    extracted_text CLOB,
    corrected_text CLOB,
    extraction_method VARCHAR(64),
    extraction_note CLOB,
    vision_summary CLOB,
    status VARCHAR(32) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    version_no INT,
    ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    type VARCHAR(40) NOT NULL,
    agent VARCHAR(32),
    target VARCHAR(32),
    summary CLOB,
    payload CLOB
);

CREATE TABLE IF NOT EXISTS memories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    task_id BIGINT,
    kind VARCHAR(32) NOT NULL,
    content CLOB NOT NULL,
    tags VARCHAR(400),
    source VARCHAR(64),
    use_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS memory_uses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    memory_id BIGINT NOT NULL,
    score DOUBLE,
    snapshot CLOB
);

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    task_id BIGINT,
    kind VARCHAR(32),
    message VARCHAR(500),
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS decisions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    version_no INT,
    checkpoint VARCHAR(40),
    action VARCHAR(40),
    note CLOB,
    details CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE tasks ADD COLUMN IF NOT EXISTS debate_intensity VARCHAR(16) DEFAULT 'balanced';
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS template_id VARCHAR(40);

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    version_no INT,
    role VARCHAR(16) NOT NULL,
    content CLOB NOT NULL,
    citations VARCHAR(500),
    engine VARCHAR(16),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
