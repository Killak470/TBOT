-- Migration to add signal tracking fields to Position table for restart persistence
-- This enables linking positions to their originating signals after application restarts

-- Add signal tracking columns to Position table
ALTER TABLE position ADD COLUMN IF NOT EXISTS original_signal_id BIGINT;
ALTER TABLE position ADD COLUMN IF NOT EXISTS order_link_id VARCHAR(255);
ALTER TABLE position ADD COLUMN IF NOT EXISTS signal_source VARCHAR(50);
ALTER TABLE position ADD COLUMN IF NOT EXISTS is_scalp_trade BOOLEAN DEFAULT FALSE;
ALTER TABLE position ADD COLUMN IF NOT EXISTS sltp_applied BOOLEAN DEFAULT FALSE;
ALTER TABLE position ADD COLUMN IF NOT EXISTS trailing_stop_initialized BOOLEAN DEFAULT FALSE;
ALTER TABLE position ADD COLUMN IF NOT EXISTS last_sltp_check TIMESTAMP;

-- Add foreign key constraint to link positions to signals
ALTER TABLE position ADD CONSTRAINT fk_position_signal 
    FOREIGN KEY (original_signal_id) REFERENCES bot_signal(id) ON DELETE SET NULL;

-- Add indexes for performance
CREATE INDEX IF NOT EXISTS idx_position_original_signal_id ON position(original_signal_id);
CREATE INDEX IF NOT EXISTS idx_position_order_link_id ON position(order_link_id);
CREATE INDEX IF NOT EXISTS idx_position_signal_source ON position(signal_source);
CREATE INDEX IF NOT EXISTS idx_position_sltp_applied ON position(sltp_applied);

-- Add orderLinkId field to BotSignal table if it doesn't exist
ALTER TABLE bot_signal ADD COLUMN IF NOT EXISTS order_link_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_bot_signal_order_link_id ON bot_signal(order_link_id);

-- Add orderLinkId field to Order table if it doesn't exist
ALTER TABLE orders ADD COLUMN IF NOT EXISTS order_link_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_orders_order_link_id ON orders(order_link_id);

-- Backfill existing positions with default values
UPDATE position 
SET 
    sltp_applied = FALSE,
    trailing_stop_initialized = FALSE,
    last_sltp_check = CURRENT_TIMESTAMP
WHERE 
    sltp_applied IS NULL 
    OR trailing_stop_initialized IS NULL 
    OR last_sltp_check IS NULL; 