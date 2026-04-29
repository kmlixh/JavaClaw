-- V28: 给 xmap 租户加一个文档型 skill,把 xmap-ol-front 注册的浏览器方法清单和调用约束教给 LLM。
-- 所有 xmap 方法都经 host.invoke 浏览器回调,不是服务端 tool。

INSERT INTO skill_definition (
    id, agent_id, skill_name, description, prompt_template, config_json,
    trigger_keywords, enabled, version, created_at, updated_at,
    scope_type, scope_tenant_id, scope_user_id, app_id
) VALUES (
    'skill-xmap-frontend-ops-v1',
    'dev-agent',
    'xmap.frontend.operations',
    '教 LLM 怎么通过 host.invoke 操作 xmap-ol-front 地图前端(打开图层/查询图层/绘图/测量/视图控制等)',
    $SKILL$
【激活场景】
用户的请求涉及 xmap 地图操作 —— 平移/缩放/底图切换/打开图层/查询图层列表/绘制/测量/UI 控制等。

【调用约束】
- 任何"操作 xmap"都必须用 host.invoke 工具调用对应方法,不要直接回答"我已经做了 X"
- host.invoke 参数:{ "method": "xmap.xxx", "payload": {...} }
- 用户消息的附件里有 host_methods.json,列出当前会话能用的方法名清单。调用前先看一眼附件确认存在
- 一次只调一个 host.invoke;等返回再发下一条
- 调完拿到 result 再用自然语言反馈给用户

【可用方法分组】

▌视图控制
- xmap.setCenter({longitude, latitude}) — 设置地图中心点
- xmap.setZoom({zoom}) — 设置缩放等级(1-18)
- xmap.fit({extent|geometry}) — 适应视图到指定范围
- xmap.goToPosition({location, zoom?}) — 跳转到位置(支持地名/坐标)
- xmap.markPosition({longitude, latitude, label?}) — 添加标记
- xmap.setBaseMap({type}) — 切换底图,type 取值 "街道图" 或 "卫星图"

▌图层管理(WMS 业务图层)
- xmap.addWmsLayer({layerId, styleId?, time?, filter?}) — 打开/添加图层。layerId 必填
- xmap.removeWmsLayer({layerId}) — 移除图层
- xmap.makeWmsLayerTop({layerId}) — 把图层置顶
- xmap.setWmsLayerStyle({layerId, styleId}) — 切样式
- xmap.setWmsLayerTime({layerId, time}) — 设时间(YYYY-MM-DD HH:mm:ss)
- xmap.previewLayerStyle({layerId, styleId}) — 预览样式

▌图层查询(信息获取类,先调用了解状态再做后续动作)
- xmap.getActiveLayers() — **获取当前已激活/打开的图层列表**,无入参。用户问"现在有什么图层"/"图层列表"/"打开 xxx 图层但不知道 ID"先调它
- xmap.getLayerOrder() — 获取图层叠放顺序
- xmap.getTopLayer() — 获取最顶层图层
- xmap.getMapState() — 获取地图当前完整状态(中心/缩放/底图等)

▌图层过滤
- xmap.addLayerFilter({layerId, filter}) — 添加过滤条件(如 "属性 X 大于 100")
- xmap.removeLayerFilter({layerId, filterId}) — 移除某个过滤条件
- xmap.clearLayerFilters({layerId}) — 清空该图层全部过滤
- xmap.getLayerFilters({layerId}) — 查询当前过滤条件
- xmap.refreshLayerFilter({layerId}) — 重新拉取过滤后的数据

▌绘图
- xmap.draw({type}) — 启动绘制,type ∈ "点" / "圆形" / "矩形" / "多边形"
- xmap.removeDrawFeature({featureId}) — 移除已绘制图形
- xmap.getDrawFeatures() — 获取所有已绘制图形
- xmap.selectGeoJson({geojson}) — 用 GeoJSON 选择/高亮区域

▌测量
- xmap.enableMeasurement({mode}) — 启用测量(距离/面积)
- xmap.disableMeasurement() — 禁用测量
- xmap.clearMeasurement() — 清空已有测量结果
- xmap.addMeasurementLayer({layerId}) / xmap.removeMeasurementLayer({layerId}) — 测量图层增删

▌UI 控制
- xmap.setUi({show, mapLayoutSwitcher?, geoSearchBar?, baseMapSwitcher?, layerController?, layerInfoPicker?, toolBox?, systemSetting?}) — 控制各 UI 组件显隐
- xmap.setLayerInfoPicker({state: true|false}) — 拾取功能开关

【典型对话示例】
用户:"现在打开了哪些图层"
你:调 host.invoke({method:"xmap.getActiveLayers"})
拿到 [{id:"l-1", name:"基站图层", ...}, {id:"l-2", name:"覆盖图层", ...}]
回:"当前打开了 2 个图层:基站图层(l-1)、覆盖图层(l-2)"

用户:"打开覆盖图层"
你:先调 xmap.getActiveLayers 查 ID(若用户没说)→ 然后 host.invoke({method:"xmap.addWmsLayer", payload:{layerId:"l-2"}})
回:"已为你打开覆盖图层"

用户:"地图缩放到云南"
你:host.invoke({method:"xmap.goToPosition", payload:{location:"云南"}}) 一步到位

【禁止】
- 不要把 xmap 方法名当作工具直接调,xmap.* 永远经 host.invoke 转一层
- 不要编造图层 ID,先 getActiveLayers 看实际有什么
- 不要一次连发多个 host.invoke,等结果再决定下一步
$SKILL$,
    NULL,
    '地图,图层,图层列表,缩放,中心,平移,定位,跳转,底图,绘制,测量,xmap,过滤',
    TRUE, 1,
    NOW(), NOW(),
    'TENANT', 'xmap', NULL, 'system-default'
)
ON CONFLICT (id) DO UPDATE SET
    description = EXCLUDED.description,
    prompt_template = EXCLUDED.prompt_template,
    trigger_keywords = EXCLUDED.trigger_keywords,
    updated_at = NOW(),
    version = skill_definition.version + 1;

-- 绑定到 dev-agent(xmap embed 默认就是这个 agent)
INSERT INTO skill_agent_binding (skill_id, agent_id) VALUES
    ('skill-xmap-frontend-ops-v1', 'dev-agent')
ON CONFLICT DO NOTHING;
