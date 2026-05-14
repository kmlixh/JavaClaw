-- V41: Agents 实验室专属权限,可以独立分配,不必跟"会话审计读全部"(session.read.all)绑死。
--
-- 之前 Agent 实验室菜单 v-if 用 session.read.all 当 gate;后端 AgentLabService 的 ALLOWED_PERMISSIONS
-- 接受 session.read.all 或 agent.lab.use 二选一。但 agent.lab.use 从未在 permission 表里
-- seed 过 → 权限管理页根本看不到这个权限,只有改 code 才能给租户管理员开放实验室。
-- 加上 menu.lab(UI 菜单层)+ agent.lab.use(业务层),两个都进字典,运维可以在
-- 权限管理 UI 里勾选分配。

INSERT INTO permission (code, category, description) VALUES
    ('menu.lab',       'menu', 'Agents 实验室菜单(系统管理员入口,迭代设计 Agent/Skill)'),
    ('agent.lab.use',  'data', '使用 Agents 实验室(创建任务 / 触发迭代 / 改写规则)')
ON CONFLICT (code) DO NOTHING;

-- 超级管理员自动拿这两个权限(role_permission 表)
INSERT INTO role_permission (role_id, permission_code) VALUES
    ('system-super-admin', 'menu.lab'),
    ('system-super-admin', 'agent.lab.use')
ON CONFLICT (role_id, permission_code) DO NOTHING;
