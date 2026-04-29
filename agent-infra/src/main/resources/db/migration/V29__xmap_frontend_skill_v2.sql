-- V29: 把 V28 之后在 dev DB 上手动迭代过的 xmap 前端方法 skill 固化为可重放的迁移。
--
-- 与 V28 的差异:
--   1. scope: TENANT/system-default → APP/xmap  (xmap 应用下公开,所有 xmap 用户可见)
--   2. 方法名约定: 移除 "xmap." 前缀,因为前端 XMapClient.registerAllMethodsToAI()
--      把方法注册到 window.AI 上时是裸名,host.invoke 必须用裸名调用
--   3. 补全 V28 漏掉的方法:
--        - getAvailableLayers (获取系统全量可用图层,与 getActiveLayers 区分)
--        - addAiToolsDialogOpenedEventListener / removeAiToolsDialogOpenedEventListener
--   4. 把 getAvailableLayers vs getActiveLayers 的区分写进 prompt,避免 LLM 混用
--   5. description / trigger_keywords 同步更新
--
-- 本迁移做 UPSERT,反复执行幂等。

INSERT INTO skill_definition (
    id, agent_id, skill_name, description, prompt_template, config_json,
    trigger_keywords, enabled, version, created_at, updated_at,
    scope_type, scope_tenant_id, scope_user_id, app_id
) VALUES (
    'skill-xmap-frontend-ops-v1',
    'dev-agent',
    'xmap.frontend.operations',
    '教 LLM 通过 host.invoke 调用 window.AI 上注册的地图方法(查可用图层/查已打开图层/打开图层/绘图/测量/视图控制/事件监听等),方法名无前缀,共 35 个',
    $XMAP$【激活场景】
用户的请求涉及 xmap 地图操作 —— 平移/缩放/底图切换/查看可用图层/打开图层/绘制/测量/UI 控制等。

【调用约束】
- 任何"操作 xmap"都必须用 host.invoke 工具调用对应方法,不要直接回答"我已经做了 X"。
- host.invoke 参数:{ "method": "<方法名>", "payload": {...} }。**方法名不带任何前缀**(没有 "xmap." 这种东西),直接写 setCenter / getActiveLayers 等。
- 下面的方法清单就是宿主当前注册到 window.AI 上的完整集合,以这份为准;消息里附带的 host_methods.json 仅作交叉确认。
- 一次只调一个 host.invoke,等返回再发下一条。
- 调完拿到 result 再用自然语言反馈给用户。
- 拿不到必填参数(如 layerId)时,先调查询类方法拿到再继续,不要瞎编 ID。

【可用方法分组】

▌视图控制
- setCenter({longitude, latitude}) — 设置地图中心点
- setZoom({zoom}) — 设置缩放等级(1-18)
- fit({extent|geometry}) — 适应视图到指定范围
- goToPosition({location, zoom?}) — 跳转到位置(支持地名/坐标)
- markPosition({longitude, latitude, label?}) — 添加标记
- setBaseMap({type}) — 切换底图,type 取值 "街道图" 或 "卫星图"

▌图层管理(WMS 业务图层)
- addWmsLayer({layerId, styleId?, time?, filters?}) — 打开/添加图层。layerId 必填。filters 可选,数组,每项 {fieldName, fieldAttName, fieldType, conditionName, conditionValue, value}
- removeWmsLayer({layerId}) — 移除图层
- makeWmsLayerTop({layerId}) — 把图层置顶
- setWmsLayerStyle({layerId, styleId}) — 切样式
- setWmsLayerTime({layerId, time}) — 设时间(YYYY-MM-DD HH:mm:ss)
- previewLayerStyle({layerId, legendTitle, sldUrl}) — 预览样式(临时)

▌图层查询(信息获取类,先调用了解状态再做后续动作)
**注意:getAvailableLayers 和 getActiveLayers 是两件事,别搞混:**
- getAvailableLayers({name?, pageSize?}) — **系统里所有可用的图层**(公共 + 用户私有的全集),返回项含 scope 字段标 'common'/'user'。用户问"**有哪些可用图层**""**有什么图层**""**想看 XX 图层但不知道 ID**" 用这个。
- getActiveLayers() — **当前已经在地图上激活/打开的子集**,无入参。用户问"**现在打开了哪些图层**""**当前显示什么**" 用这个。
- getLayerOrder() — 获取图层叠放顺序
- getTopLayer() — 获取最顶层图层
- getMapState() — 获取地图当前完整状态(中心/缩放/底图等)

▌图层过滤
- addLayerFilter({layerId, filter}) — 添加过滤条件
- removeLayerFilter({layerId, filterId}) — 移除某个过滤条件
- clearLayerFilters({layerId}) — 清空该图层全部过滤
- getLayerFilters({layerId}) — 查询当前过滤条件
- refreshLayerFilter({layerId}) — 重新拉取过滤后的数据

▌绘图
- draw({type}) — 启动绘制,type ∈ "点" / "圆形" / "矩形" / "多边形"
- removeDrawFeature({featureId}) — 移除已绘制图形
- getDrawFeatures() — 获取所有已绘制图形
- selectGeoJson({geojson}) — 用 GeoJSON 选择/高亮区域

▌测量
- enableMeasurement({mode}) — 启用测量(距离/面积)
- disableMeasurement() — 禁用测量
- clearMeasurement() — 清空已有测量结果
- addMeasurementLayer({layerId}) / removeMeasurementLayer({layerId}) — 测量图层增删

▌UI 控制
- setUi({show, mapLayoutSwitcher?, geoSearchBar?, baseMapSwitcher?, layerController?, layerInfoPicker?, toolBox?, systemSetting?}) — 控制各 UI 组件显隐
- setLayerInfoPicker({state: true|false}) — 拾取功能开关

▌事件监听(高级,通常不需要主动调,除非用户明确说"监听 X 事件")
- addAiToolsDialogOpenedEventListener({listenerId}) — 注册"AI 工具弹窗打开"监听
- removeAiToolsDialogOpenedEventListener({listenerId}) — 取消监听

【典型对话示例】

用户:"现在有哪些可用图层"  / "有什么图层" / "可以看哪些图层"
你:调 host.invoke({ method: "getAvailableLayers" })
拿到 [{layerId:"l-1", layerName:"基站图层", scope:"common", ...}, ...]
回:"系统当前可用 N 个图层,包括 基站图层(l-1)、覆盖图层(l-2)、…"

用户:"现在打开了哪些图层"  / "当前显示什么"
你:调 host.invoke({ method: "getActiveLayers" })
拿到 [{layerId:"l-2", layerName:"覆盖图层", styleId:..., order:...}]
回:"当前打开了 1 个图层:覆盖图层(l-2)"

用户:"打开覆盖图层"
你:先 host.invoke({ method: "getAvailableLayers", payload: { name: "覆盖" } }) 找到 ID
然后 host.invoke({ method: "addWmsLayer", payload: { layerId: "<找到的 layerId>" } })
回:"已为你打开覆盖图层"

用户:"地图缩放到云南"
你:host.invoke({ method: "goToPosition", payload: { location: "云南" } }) 一步到位

【禁止】
- 方法名不要带任何前缀,**别再写 "xmap." 了** —— 注册到 window.AI 上是裸名
- 不要把"可用图层"和"已激活图层"混为一谈 —— 两个方法返回不同东西,看用户具体问的是哪种
- 不要把方法名当作工具直接调,所有方法都经 host.invoke 转一层
- 不要编造图层 ID,先 getAvailableLayers / getActiveLayers 看实际有什么
- 不要一次连发多个 host.invoke,等结果再决定下一步$XMAP$,
    NULL,
    '地图,图层,可用图层,有哪些图层,图层列表,打开图层,关闭图层,缩放,缩放级别,中心,平移,定位,跳转,底图,街道图,卫星图,绘制,测量,xmap,过滤,视野,覆盖范围,标记,拾取',
    TRUE, 5,
    NOW(), NOW(),
    'APP', 'xmap', NULL, 'xmap'
)
ON CONFLICT (id) DO UPDATE SET
    skill_name       = EXCLUDED.skill_name,
    description      = EXCLUDED.description,
    prompt_template  = EXCLUDED.prompt_template,
    trigger_keywords = EXCLUDED.trigger_keywords,
    scope_type       = EXCLUDED.scope_type,
    scope_tenant_id  = EXCLUDED.scope_tenant_id,
    scope_user_id    = EXCLUDED.scope_user_id,
    app_id           = EXCLUDED.app_id,
    enabled          = EXCLUDED.enabled,
    updated_at       = NOW(),
    version          = GREATEST(skill_definition.version, EXCLUDED.version);

INSERT INTO skill_agent_binding (skill_id, agent_id) VALUES
    ('skill-xmap-frontend-ops-v1', 'dev-agent')
ON CONFLICT DO NOTHING;
