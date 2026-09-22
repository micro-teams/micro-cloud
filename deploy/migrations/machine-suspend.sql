BEGIN;
ALTER TABLE microcloud.machine DROP CONSTRAINT IF EXISTS machine_status_check;
ALTER TABLE microcloud.machine ADD CONSTRAINT machine_status_check CHECK
    (status IN ('PROVISIONING', 'STARTING', 'RUNNING', 'SUSPENDING', 'SUSPENDED',
                'RESUMING', 'STOPPING', 'STOPPED', 'DELETING', 'DELETED', 'ERROR'));
ALTER TABLE microcloud.machine_event DROP CONSTRAINT IF EXISTS machine_event_action_check;
ALTER TABLE microcloud.machine_event ADD CONSTRAINT machine_event_action_check CHECK
    (action IN ('PROVISION', 'START', 'SUSPEND', 'RESUME', 'SHUTDOWN', 'STOP',
                'DELETE', 'AI_SWITCH', 'AI_LOGIN'));
COMMIT;
