-- Container-level monitoring page: "which service crashed how many times,
-- restarted how many times, which node it's deployed on" - Docker's own
-- RestartCount (from `docker inspect`) resets to 0 whenever a container gets
-- recreated (not just restarted), which happens often in this environment
-- (server reboots, docker daemon restarts) - so a durable counter that
-- survives recreation needs its own table rather than trusting Docker's
-- transient value. container_status holds the latest polled snapshot per
-- container; container_event is an append-only log of detected
-- restart/crash/recreation transitions for the history timeline.
CREATE TABLE container_status (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  container_name VARCHAR(100) NOT NULL,
  node VARCHAR(100) NOT NULL,
  image VARCHAR(255),
  state VARCHAR(20) NOT NULL,
  status_text VARCHAR(100),
  docker_restart_count INT NOT NULL DEFAULT 0,
  cumulative_restart_count INT NOT NULL DEFAULT 0,
  -- Docker's own container ID (not the compose service name) - a full
  -- recreation (docker rm + recreate, or the whole daemon/host restarting a
  -- container from scratch) gets a brand new ID and resets
  -- docker_restart_count to 0, which looks identical to "never crashed" if
  -- only docker_restart_count is compared. Tracking the last-seen ID lets
  -- the poller tell "recreated" (ID changed) apart from "in-place restart"
  -- (same ID, docker_restart_count went up) - both count toward
  -- cumulative_restart_count, but only one is a real Docker-tracked restart.
  last_container_id VARCHAR(70),
  started_at DATETIME,
  last_polled_at DATETIME NOT NULL,
  UNIQUE KEY uk_container_status_name (container_name)
);

CREATE TABLE container_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  container_name VARCHAR(100) NOT NULL,
  event_type VARCHAR(20) NOT NULL,
  detail VARCHAR(500),
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_container_event_name (container_name),
  INDEX idx_container_event_occurred (occurred_at)
);

INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(110, 40, '容器监控', '/realtime/container-monitoring', 'ContainerMonitoringPage', 'ClusterOutlined', 'realtime:container:view', 'MENU', 50, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id >= 110;
