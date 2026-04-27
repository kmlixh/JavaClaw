<script setup>
import * as echarts from "echarts";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";

const props = defineProps({
  option: {
    type: Object,
    required: true
  }
});

const root = ref(null);
const renderError = ref("");
// 由 computeChartHeight 根据 option 内容推导出来的目标高度(px)。容器 min-height 用这个值,
// 外层 .markdown-embedded-chart 会自适应到这个高度 —— 老问题就是硬钉 320px 遇到类目多或
// legend 长就溢出被裁。现在随 option 动态算。
const chartHeight = ref(360);
let chart = null;

// LLM 手写的 option 经常漏 xAxis/yAxis(看到过"xAxis '0' not found"报错),帮它补齐默认直角坐标系。
// 仅针对 cartesian 类的 series(bar/line/scatter/...) 生效;饼图/雷达图等不需要 xAxis/yAxis 的不碰。
const CARTESIAN_SERIES_TYPES = new Set([
  "bar", "line", "scatter", "effectScatter", "heatmap", "boxplot", "candlestick", "themeRiver"
]);
const RADIAL_SERIES_TYPES = new Set(["pie", "radar", "gauge", "funnel", "sunburst", "treemap"]);

function asArray(x) {
  if (!x) return [];
  return Array.isArray(x) ? x : [x];
}

/**
 * series.data 里如果是对象形式(常见写法: {name, value}),把 name 抽成类目标签数组。
 * ECharts 不像饼图那样会自动从 cartesian 系列的 data[i].name 推导 xAxis 类目,所以需要我们
 * 主动抽出来回填到 xAxis.data / yAxis.data。
 * 不是对象形态(纯数值数组 / [cat, val] 二元组 / 空)时返回 null,让调用方回退到索引占位。
 */
function extractCategoryNames(seriesData) {
  if (!Array.isArray(seriesData) || seriesData.length === 0) return null;
  const names = [];
  for (const item of seriesData) {
    if (item && typeof item === "object" && !Array.isArray(item) && item.name !== undefined) {
      names.push(String(item.name));
    } else {
      return null; // 一旦有一项不带 name,整组就不是"对象形态 series data",退回默认
    }
  }
  return names;
}

function sanitizeOption(raw) {
  if (!raw || typeof raw !== "object") return raw;
  // 深拷贝避免污染上游的原始对象;LLM 可能复用同一个 option 传给多个 block。
  const opt = JSON.parse(JSON.stringify(raw));
  const seriesList = asArray(opt.series);
  const needsCartesian = seriesList.some((s) => s && CARTESIAN_SERIES_TYPES.has(s.type));
  if (needsCartesian) {
    const firstData = Array.isArray(seriesList[0]?.data) ? seriesList[0].data : [];
    const namesFromSeries = extractCategoryNames(firstData);
    // 默认把 category 走 xAxis,除非用户显式给了 yAxis=category(水平 bar)
    const yAxisGivenIsCategory = asArray(opt.yAxis).some((a) => a && a.type === "category");
    if (opt.xAxis === undefined || opt.xAxis === null) {
      if (!yAxisGivenIsCategory) {
        const categories = namesFromSeries
            ? namesFromSeries
            : firstData.map((_, idx) => String(idx + 1));
        opt.xAxis = { type: "category", data: categories };
      } else {
        opt.xAxis = { type: "value" };
      }
    } else {
      // xAxis 给了但 type=category + data 为空 / 缺失 → 用 series name 补;否则按作者给的来。
      const xAxisArr = asArray(opt.xAxis);
      xAxisArr.forEach((axis) => {
        if (axis && axis.type === "category" && (!Array.isArray(axis.data) || axis.data.length === 0) && namesFromSeries) {
          axis.data = namesFromSeries;
        }
      });
      opt.xAxis = Array.isArray(opt.xAxis) ? xAxisArr : xAxisArr[0];
    }
    if (opt.yAxis === undefined || opt.yAxis === null) {
      // 如果 xAxis 不是 category 而 series data 有 name,做成水平 bar:yAxis=category + 类目
      if (asArray(opt.xAxis).every((a) => !a || a.type !== "category") && namesFromSeries) {
        opt.yAxis = { type: "category", data: namesFromSeries };
      } else {
        opt.yAxis = { type: "value" };
      }
    } else {
      const yAxisArr = asArray(opt.yAxis);
      yAxisArr.forEach((axis) => {
        if (axis && axis.type === "category" && (!Array.isArray(axis.data) || axis.data.length === 0) && namesFromSeries) {
          axis.data = namesFromSeries;
        }
      });
      opt.yAxis = Array.isArray(opt.yAxis) ? yAxisArr : yAxisArr[0];
    }
    // 关键:LLM 写的 option 基本都没指定 grid。ECharts 默认 grid 不把轴标签算进去,柱子会撑满
    // 可见区但 x 轴类目文字 / y 轴刻度 / legend 被裁到 canvas 边缘外 —— 就是用户看到"柱状图很高
    // 但底部文字看不到"的原因。强制注入 containLabel:true 并预留顶底 margin 给 title/legend。
    const hasTitle = !!(opt.title && (opt.title.text || opt.title.subtext));
    const hasLegend = !!opt.legend;
    // legend 默认在顶;显式 bottom 的情况 grid.bottom 要让位
    const legendAtBottom = !!(opt.legend && (
        opt.legend.bottom !== undefined
        || opt.legend.top === "bottom"
        || opt.legend.orient === "horizontal" && opt.legend.top === undefined && opt.legend.bottom === undefined && hasTitle
    ));
    const topPad = (hasTitle ? 56 : 0) + (hasLegend && !legendAtBottom ? 40 : 16);
    const bottomPad = 48 + (legendAtBottom ? 40 : 0);
    const baseGrid = {
      left: 24,
      right: 24,
      top: topPad,
      bottom: bottomPad,
      containLabel: true
    };
    if (!opt.grid) {
      opt.grid = baseGrid;
    } else {
      // 作者给了自定义 grid,保留,但强制 containLabel 避免截字
      opt.grid = Object.assign({}, baseGrid, opt.grid, { containLabel: true });
    }
  }
  return opt;
}

/**
 * 根据 option 内容估算合理高度。在 sanitize 已经保证 grid.containLabel=true + 顶底 margin
 * 预留 title/legend 空间的前提下,这里只需要算"绘图区域(grid 内容)需要多少像素"再加上预留
 * chrome 的总值。
 *   - 径向图:正方形比例,500px 足够展示饼/雷达 + 图例
 *   - 笛卡尔图:核心是 category 数 × 每类目最小高
 *     - 水平 bar (yAxis=category):每类目要 40px,柱子 + 类目文字都在这里
 *     - 竖直 bar/line (xAxis=category):垂直方向不随类目数变化,用一个稳定高度;
 *       类目多时 xAxis 文字会旋转,已由 grid.bottom 预留
 *   - 无法判定时给 480 默认
 * 上下限 320 ~ 1100,避免太矮裁内容或太高浪费屏幕。
 */
function computeChartHeight(opt) {
  const MIN = 320;
  const MAX = 1100;
  const DEFAULT = 480;
  if (!opt || typeof opt !== "object") return DEFAULT;
  const seriesList = asArray(opt.series);
  const hasTitle = !!(opt.title && (opt.title.text || opt.title.subtext));
  const hasLegend = !!opt.legend;
  // chrome = title + legend + grid 上下 margin (sanitize 里定死的 topPad + bottomPad 之和)
  const chromeExtra = (hasTitle ? 56 : 0) + (hasLegend ? 56 : 0) + 80;

  const radial = seriesList.some((s) => s && RADIAL_SERIES_TYPES.has(s.type));
  if (radial) return Math.min(MAX, Math.max(MIN, 460 + chromeExtra));

  const cartesian = seriesList.some((s) => s && CARTESIAN_SERIES_TYPES.has(s.type));
  if (!cartesian) return DEFAULT;

  const yAxisArr = asArray(opt.yAxis);
  const xAxisArr = asArray(opt.xAxis);
  const horizontal = yAxisArr.some((a) => a && a.type === "category");

  let categoryCount = 0;
  if (horizontal) {
    const cat = yAxisArr.find((a) => a && a.type === "category");
    categoryCount = Array.isArray(cat?.data) ? cat.data.length : 0;
  } else {
    const cat = xAxisArr.find((a) => a && a.type === "category");
    categoryCount = Array.isArray(cat?.data) ? cat.data.length : 0;
  }
  if (!categoryCount && seriesList[0]?.data?.length) {
    categoryCount = seriesList[0].data.length;
  }
  if (!categoryCount) return Math.min(MAX, Math.max(MIN, DEFAULT + chromeExtra));

  // 水平 bar:每类目 40px(含柱体 + 类目文字);竖直方向稳定高度,类目多由 xAxis 旋转处理
  const perCategory = horizontal ? 40 : 0;
  const gridArea = horizontal
    ? categoryCount * perCategory
    : 360;
  return Math.min(MAX, Math.max(MIN, gridArea + chromeExtra));
}

const safeOption = computed(() => sanitizeOption(props.option));

function render() {
  if (!root.value) return;
  try {
    const opt = safeOption.value || {};
    const nextHeight = computeChartHeight(opt);
    chartHeight.value = nextHeight;
    if (!chart) {
      chart = echarts.init(root.value);
    } else {
      // 高度变了要先 resize,setOption 才不会按旧尺寸布局
      chart.resize();
    }
    chart.setOption(opt, true);
    renderError.value = "";
  } catch (error) {
    // option 实在坏到没救(series 里含非法类型、data 不是 array 等),走兜底文案,不要让整页崩。
    // 原始异常记录到 console 便于定位,但不走 throw。
    console.warn("[echarts.render_failed]", error);
    renderError.value = String(error?.message || error);
    if (chart) {
      try { chart.clear(); } catch (_) { /* ignore */ }
    }
  }
}

function handleResize() {
  if (chart) {
    try { chart.resize(); } catch (_) { /* ignore */ }
  }
}

onMounted(() => {
  render();
  // 容器尺寸变化时主动 resize(父气泡折叠/展开或窗口 resize);不靠 echarts 自己的监听。
  window.addEventListener("resize", handleResize);
});

watch(() => props.option, () => render(), { deep: true });
// chartHeight 变化后,下一帧再 resize 让 canvas 重新吃满容器。
watch(chartHeight, () => {
  requestAnimationFrame(() => handleResize());
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", handleResize);
  if (chart) {
    chart.dispose();
    chart = null;
  }
});
</script>

<template>
  <div class="echarts-block-wrap" :style="{ height: chartHeight + 'px' }">
    <div v-show="!renderError" ref="root" class="echarts-block"></div>
    <pre v-if="renderError" class="echarts-block-error">图表配置错误：{{ renderError }}

原始 option：
{{ JSON.stringify(props.option, null, 2) }}</pre>
  </div>
</template>

<style scoped>
.echarts-block-wrap {
  width: 100%;
  /* 高度由 :style 动态给,这里只设默认兜底 */
  min-height: 280px;
}
.echarts-block-error {
  padding: 10px 12px;
  background: #fff4f4;
  border: 1px solid #f2caca;
  border-radius: 6px;
  color: #8b2222;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow: auto;
}
</style>
