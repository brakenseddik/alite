CREATE TABLE categories
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id   UUID         NOT NULL DEFAULT gen_random_uuid(),
    title       VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    icon        VARCHAR(255),
    level       SMALLINT     NOT NULL,
    parent_id   BIGINT
        CONSTRAINT fk_categories_parent REFERENCES categories (id) ON DELETE RESTRICT,
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_categories_public_id UNIQUE (public_id),
    CONSTRAINT chk_categories_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT chk_categories_level_range CHECK (level BETWEEN 1 AND 3),
    -- only level 1 has no parent 1:TOP, 2:SUB, 3:DET
    CONSTRAINT chk_categories_root CHECK ((level = 1) = (parent_id IS NULL)),
    -- prevent category as its own parent
    CONSTRAINT chk_categories_own_parent CHECK (parent_id != id)
);

CREATE TABLE products
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id   UUID           NOT NULL DEFAULT gen_random_uuid(),
    sku         VARCHAR(64)    NOT NULL,
    title       VARCHAR(255)   NOT NULL,
    description TEXT,
    price       NUMERIC(12, 2) NOT NULL,
    currency    CHAR(3)        NOT NULL DEFAULT 'EUR',
    category_id BIGINT         NOT NULL
        CONSTRAINT fk_products_category REFERENCES categories (id) ON DELETE RESTRICT,
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uq_products_public_id UNIQUE (public_id),
    CONSTRAINT chk_products_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT chk_products_price_non_negative CHECK (price >= 0),
    CONSTRAINT chk_products_sku_format CHECK (sku ~ '^[A-Z0-9][A-Z0-9._-]*$')
);

-- SKU must be unique among non-deleted products
CREATE UNIQUE INDEX uq_products_sku_active
    ON products (sku) WHERE deleted_at IS NULL;