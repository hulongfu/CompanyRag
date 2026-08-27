# Database Design Patterns

## Common Table Patterns

### Base Table Pattern

All tables should include standard columns:

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE,  -- Soft delete
    -- ... other columns
);

-- Index for soft delete queries
CREATE INDEX idx_users_deleted_at ON users(deleted_at) WHERE deleted_at IS NULL;
```

### UUID Pattern

For distributed systems or when you need globally unique identifiers:

```sql
CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    -- ... other columns
);
```

### Status Field Pattern

Use ENUMs for status fields:

```sql
CREATE TYPE order_status AS ENUM ('pending', 'processing', 'shipped', 'delivered', 'cancelled');

CREATE TABLE orders (
    id SERIAL PRIMARY KEY,
    status order_status DEFAULT 'pending',
    -- ... other columns
);
```

## Relationship Patterns

### One-to-One Relationship

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    -- ... other columns
);

CREATE TABLE user_profiles (
    id SERIAL PRIMARY KEY,
    user_id INTEGER UNIQUE NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bio TEXT,
    avatar_url VARCHAR(255),
    -- ... other columns
);

CREATE INDEX idx_user_profiles_user_id ON user_profiles(user_id);
```

### One-to-Many Relationship

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    -- ... other columns
);

CREATE TABLE posts (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    content TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    -- ... other columns
);

CREATE INDEX idx_posts_user_id ON posts(user_id);
CREATE INDEX idx_posts_created_at ON posts(created_at DESC);
```

### Many-to-Many Relationship

```sql
CREATE TABLE posts (
    id SERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    -- ... other columns
);

CREATE TABLE tags (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) UNIQUE NOT NULL,
    -- ... other columns
);

CREATE TABLE post_tags (
    post_id INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, tag_id)
);

CREATE INDEX idx_post_tags_post_id ON post_tags(post_id);
CREATE INDEX idx_post_tags_tag_id ON post_tags(tag_id);
```

### Self-Referencing Relationships (Hierarchical Data)

```sql
-- Adjacency List Pattern
CREATE TABLE categories (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    parent_id INTEGER REFERENCES categories(id) ON DELETE SET NULL,
    path VARCHAR(1000),  -- For efficient path queries
    level INTEGER DEFAULT 0,
    -- ... other columns
);

CREATE INDEX idx_categories_parent_id ON categories(parent_id);
CREATE INDEX idx_categories_path ON categories(path);
```

## Data Integrity Patterns

### Check Constraints

```sql
CREATE TABLE products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    quantity INTEGER NOT NULL,
    CHECK (price > 0),
    CHECK (quantity >= 0),
    -- ... other columns
);
```

### Unique Constraints

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    username VARCHAR(50) NOT NULL,
    UNIQUE (email),
    UNIQUE (username),
    -- ... other columns
);

-- Composite unique constraint
CREATE TABLE user_skills (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    skill_name VARCHAR(100) NOT NULL,
    level INTEGER NOT NULL,
    UNIQUE (user_id, skill_name),
    -- ... other columns
);
```

### Foreign Key Strategies

```sql
-- CASCADE: Delete child records when parent is deleted
CREATE TABLE comments (
    id SERIAL PRIMARY KEY,
    post_id INTEGER REFERENCES posts(id) ON DELETE CASCADE,
    content TEXT NOT NULL
);

-- SET NULL: Set foreign key to NULL when parent is deleted
CREATE TABLE user_profiles (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    bio TEXT
);

-- RESTRICT: Prevent deletion if child records exist (default)
CREATE TABLE orders (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id) ON DELETE RESTRICT,
    total_amount DECIMAL(10, 2) NOT NULL
);

-- NO ACTION: Similar to RESTRICT, but check is deferred
CREATE TABLE payments (
    id SERIAL PRIMARY KEY,
    order_id INTEGER REFERENCES orders(id) ON DELETE NO ACTION,
    amount DECIMAL(10, 2) NOT NULL
);
```

## Indexing Strategies

### When to Create Indexes

Create indexes on:
- Columns frequently used in WHERE clauses
- Columns used in JOIN conditions
- Columns used in ORDER BY clauses
- Columns used in GROUP BY clauses
- Foreign key columns (for JOIN performance)

### Index Types

```sql
-- B-tree index (default, most common)
CREATE INDEX idx_users_email ON users(email);

-- Unique index
CREATE UNIQUE INDEX idx_users_username ON users(username);

-- Composite index (multiple columns)
CREATE INDEX idx_posts_user_created ON posts(user_id, created_at DESC);

-- Partial index (with condition)
CREATE INDEX idx_active_users ON users(email) WHERE deleted_at IS NULL;

-- Hash index (good for equality comparisons)
CREATE INDEX idx_users_email_hash ON users USING HASH(email);

-- GIN index (for array, JSONB, full-text search)
CREATE INDEX idx_posts_tags ON posts USING GIN(tags);
CREATE INDEX idx_documents_content ON documents USING GIN(to_tsvector('english', content));

-- BRIN index (good for very large tables with natural ordering)
CREATE INDEX idx_logs_created_at ON logs USING BRIN(created_at);
```

## Audit Trail Pattern

```sql
CREATE TABLE audit_logs (
    id SERIAL PRIMARY KEY,
    table_name VARCHAR(255) NOT NULL,
    record_id INTEGER NOT NULL,
    action VARCHAR(50) NOT NULL,  -- INSERT, UPDATE, DELETE
    old_values JSONB,
    new_values JSONB,
    changed_by INTEGER NOT NULL REFERENCES users(id),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_table_record ON audit_logs(table_name, record_id);
CREATE INDEX idx_audit_logs_changed_at ON audit_logs(changed_at DESC);

-- Trigger to automatically create audit logs
CREATE OR REPLACE FUNCTION create_audit_log()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        INSERT INTO audit_logs (table_name, record_id, action, old_values, changed_by)
        VALUES (TG_TABLE_NAME, OLD.id, 'DELETE', row_to_json(OLD), current_setting('app.current_user_id')::INTEGER);
        RETURN OLD;
    ELSIF TG_OP = 'UPDATE' THEN
        INSERT INTO audit_logs (table_name, record_id, action, old_values, new_values, changed_by)
        VALUES (TG_TABLE_NAME, NEW.id, 'UPDATE', row_to_json(OLD), row_to_json(NEW), current_setting('app.current_user_id')::INTEGER);
        RETURN NEW;
    ELSIF TG_OP = 'INSERT' THEN
        INSERT INTO audit_logs (table_name, record_id, action, new_values, changed_by)
        VALUES (TG_TABLE_NAME, NEW.id, 'INSERT', row_to_json(NEW), current_setting('app.current_user_id')::INTEGER);
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Usage
CREATE TRIGGER users_audit_trigger
AFTER INSERT OR UPDATE OR DELETE ON users
FOR EACH ROW EXECUTE FUNCTION create_audit_log();
```

## Soft Delete Pattern

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE,
    -- ... other columns
);

-- Index for filtering non-deleted records
CREATE INDEX idx_users_deleted_at ON users(deleted_at) WHERE deleted_at IS NULL;

-- Query pattern
SELECT * FROM users WHERE deleted_at IS NULL;

-- Soft delete operation
UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?;
```

## Data Versioning Pattern

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    -- ... other columns
);

CREATE TABLE user_versions (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    email VARCHAR(255) NOT NULL,
    -- ... other columns
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, version)
);

CREATE INDEX idx_user_versions_user_id ON user_versions(user_id);
CREATE INDEX idx_user_versions_created_at ON user_versions(created_at DESC);

-- Trigger to automatically create versions
CREATE OR REPLACE FUNCTION create_user_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO user_versions (user_id, version, email, ...)
    VALUES (
        NEW.id,
        COALESCE((SELECT MAX(version) FROM user_versions WHERE user_id = NEW.id), 0) + 1,
        NEW.email,
        ...
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_version_trigger
AFTER INSERT OR UPDATE ON users
FOR EACH ROW EXECUTE FUNCTION create_user_version();
```

## Common Field Types

### String Types

```sql
-- Fixed-length strings
CHAR(10)           -- Always 10 characters, padded with spaces

-- Variable-length strings (most common)
VARCHAR(255)       -- Up to 255 characters
VARCHAR(50)        -- Up to 50 characters
TEXT               -- Unlimited length

-- Use TEXT for long text content
-- Use VARCHAR with appropriate length for email, username, etc.
-- Use CHAR only for fixed-length codes (e.g., ISO country codes)
```

### Numeric Types

```sql
-- Integer types
SMALLINT           -- 2 bytes, -32768 to 32767
INTEGER            -- 4 bytes, -2.1B to 2.1B
BIGINT             -- 8 bytes, -9.2E18 to 9.2E18
SERIAL             -- Auto-incrementing INTEGER

-- Decimal/numeric types (exact precision)
DECIMAL(10, 2)     -- 10 total digits, 2 after decimal point
NUMERIC(15, 4)     -- 15 total digits, 4 after decimal point
MONEY              -- Currency type, 2 decimal places

-- Floating-point types (approximate)
REAL               -- 4 bytes, 6 decimal digits precision
DOUBLE PRECISION   -- 8 bytes, 15 decimal digits precision
```

### Date/Time Types

```sql
-- Date and time types
DATE               -- Date only (year, month, day)
TIME               -- Time only (hour, minute, second)
TIMESTAMP          -- Date and time without timezone
TIMESTAMP WITH TIME ZONE  -- Date and time with timezone
INTERVAL           -- Time interval

-- Usage examples
CURRENT_DATE       -- Current date
CURRENT_TIME       -- Current time
CURRENT_TIMESTAMP  -- Current timestamp
NOW()              -- Same as CURRENT_TIMESTAMP
```

### Boolean Type

```sql
-- Boolean type
BOOLEAN            -- TRUE, FALSE, or NULL

-- Alternative (for older databases)
SMALLINT           -- 0 or 1 (some databases)
```

### JSON/JSONB Types

```sql
-- JSON type (text-based, preserves formatting)
CREATE TABLE products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    metadata JSON
);

-- JSONB type (binary, faster, supports indexing)
CREATE TABLE products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    metadata JSONB
);

-- Querying JSONB
SELECT * FROM products WHERE metadata->>'price'::NUMERIC > 100;
SELECT * FROM products WHERE metadata ? 'color';
SELECT * FROM products WHERE metadata @> '{"category": "electronics"}';

-- Indexing JSONB
CREATE INDEX idx_products_metadata ON products USING GIN(metadata);
```

### Array Types

```sql
-- Array type
CREATE TABLE posts (
    id SERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    tags TEXT[],
    categories INTEGER[]
);

-- Querying arrays
SELECT * FROM posts WHERE 'python' = ANY(tags);
SELECT * FROM posts WHERE tags @> ARRAY['python', 'fastapi'];
SELECT * FROM posts WHERE ARRAY_LENGTH(tags, 1) > 0;

-- Indexing arrays
CREATE INDEX idx_posts_tags ON posts USING GIN(tags);
```

## Migration Best Practices

### Use Migration Tools

- **Alembic** (Python/SQLAlchemy)
- **Flyway** (Java)
- **Liquibase** (Java)
- **Dbmate** (Language-agnostic)

### Migration Naming Convention

```bash
# Format: YYYYMMDDHHMMSS_description
20240322153000_create_users_table.up.sql
20240322153000_create_users_table.down.sql

# Or descriptive naming
001_create_users_table.sql
002_add_email_verification.sql
003_add_user_roles.sql
```

### Migration Template

```sql
-- up.sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- down.sql
DROP TABLE users;
```

## Performance Optimization

### Query Optimization Tips

1. **Use EXPLAIN ANALYZE** to understand query performance
2. **Avoid SELECT \*** in production code
3. **Use indexes** on frequently queried columns
4. **Use connection pooling** for high-traffic applications
5. **Use prepared statements** to prevent SQL injection
6. **Avoid N+1 queries** by using joins or subqueries
7. **Use pagination** for large result sets
8. **Denormalize** frequently accessed data if necessary

### Partitioning

For very large tables:

```sql
-- Range partitioning by date
CREATE TABLE logs (
    id SERIAL,
    created_at TIMESTAMP WITH TIME ZONE,
    message TEXT
) PARTITION BY RANGE (created_at);

CREATE TABLE logs_2024_01 PARTITION OF logs
FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');

CREATE TABLE logs_2024_02 PARTITION OF logs
FOR VALUES FROM ('2024-02-01') TO ('2024-03-01');
```

## Security Best Practices

### Password Storage

```sql
-- Never store plain text passwords!
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,  -- Bcrypt, Argon2, or PBKDF2
    -- ... other columns
);
```

### Sensitive Data

```sql
-- Use encryption for sensitive data
CREATE TABLE credit_cards (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id),
    card_number_encrypted BYTEA,  -- Encrypted at rest
    card_last4 VARCHAR(4),        -- Display purpose only
    expiry_date VARCHAR(5),
    -- ... other columns
);
```

### Row-Level Security

```sql
-- Enable RLS on table
ALTER TABLE documents ENABLE ROW LEVEL SECURITY;

-- Create policy: users can only see their own documents
CREATE POLICY user_documents_policy ON documents
FOR ALL
USING (user_id = current_user_id())
WITH CHECK (user_id = current_user_id());
```

## Common Table Examples

### Users Table

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(50) UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    avatar_url VARCHAR(500),
    is_active BOOLEAN DEFAULT TRUE,
    is_verified BOOLEAN DEFAULT FALSE,
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_is_active ON users(is_active) WHERE deleted_at IS NULL;
```

### Posts Table

```sql
CREATE TABLE posts (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    slug VARCHAR(255) UNIQUE,
    content TEXT,
    excerpt VARCHAR(500),
    featured_image_url VARCHAR(500),
    status VARCHAR(20) DEFAULT 'draft',  -- draft, published, archived
    published_at TIMESTAMP WITH TIME ZONE,
    view_count INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_posts_user_id ON posts(user_id);
CREATE INDEX idx_posts_slug ON posts(slug);
CREATE INDEX idx_posts_status ON posts(status) WHERE deleted_at IS NULL;
CREATE INDEX idx_posts_published_at ON posts(published_at DESC) WHERE status = 'published';
```

### Comments Table

```sql
CREATE TABLE comments (
    id SERIAL PRIMARY KEY,
    post_id INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    parent_id INTEGER REFERENCES comments(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    is_approved BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_comments_post_id ON comments(post_id);
CREATE INDEX idx_comments_user_id ON comments(user_id);
CREATE INDEX idx_comments_parent_id ON comments(parent_id);
CREATE INDEX idx_comments_is_approved ON comments(is_approved);
```

### Settings Table (Key-Value Store)

```sql
CREATE TABLE settings (
    id SERIAL PRIMARY KEY,
    key VARCHAR(255) UNIQUE NOT NULL,
    value TEXT,
    value_type VARCHAR(20) DEFAULT 'string',  -- string, integer, boolean, json
    description TEXT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_settings_key ON settings(key);
```

### Tags Table (Many-to-Many)

```sql
CREATE TABLE tags (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) UNIQUE NOT NULL,
    slug VARCHAR(50) UNIQUE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE post_tags (
    post_id INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, tag_id)
);

CREATE INDEX idx_post_tags_post_id ON post_tags(post_id);
CREATE INDEX idx_post_tags_tag_id ON post_tags(tag_id);
```
