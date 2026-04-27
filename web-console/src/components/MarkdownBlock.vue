<script setup>
import { computed } from "vue";
import EchartsBlock from "./EchartsBlock.vue";

// 把一段含有 ```echarts {json} ``` 代码块的 markdown 切成 text/echarts 段,
// text 段仍然走 App.vue 里的 markdownToHtml 渲染(沿用原有解析器),
// echarts 段用 <EchartsBlock> 组件挂真实交互图表。
//
// 为什么要传一个 toHtml 函数进来:App.vue 里的 markdownToHtml 是多年堆出来的 inline
// 实现(表格/列表/代码块/heading/quote 等),没必要在这里重新写一遍 —— 父级当参数传进来
// 最简单也最稳定。
const props = defineProps({
  markdown: {
    type: String,
    default: ""
  },
  toHtml: {
    type: Function,
    required: true
  }
});

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

const segments = computed(() => {
  const text = String(props.markdown || "");
  const lines = text.replace(/\r\n/g, "\n").split("\n");
  const out = [];
  let buffer = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index];
    const trimmed = line.trim();
    // 识别 ```echarts 或 ```chart(宽松一点,两个别名都认)
    if (/^```(echarts|chart)\s*$/i.test(trimmed)) {
      if (buffer.length) {
        out.push({ type: "text", html: props.toHtml(buffer.join("\n")) });
        buffer = [];
      }
      index += 1;
      const jsonLines = [];
      while (index < lines.length && !/^```\s*$/.test(lines[index].trim())) {
        jsonLines.push(lines[index]);
        index += 1;
      }
      if (index < lines.length) {
        index += 1; // 跳过闭合 ```
      }
      const raw = jsonLines.join("\n").trim();
      if (!raw) {
        continue;
      }
      try {
        const option = JSON.parse(raw);
        out.push({ type: "echarts", option });
      } catch (error) {
        // JSON 坏掉就降级成代码块展示,避免整个 markdown 断崖
        out.push({
          type: "text",
          html: `<pre class="md-echarts-error">echarts 代码块解析失败: ${escapeHtml(
            error?.message || error
          )}\n\n${escapeHtml(raw)}</pre>`
        });
      }
      continue;
    }
    buffer.push(line);
    index += 1;
  }
  if (buffer.length) {
    out.push({ type: "text", html: props.toHtml(buffer.join("\n")) });
  }
  return out;
});
</script>

<template>
  <div class="markdown-block-root">
    <template v-for="(segment, i) in segments" :key="i">
      <EchartsBlock
        v-if="segment.type === 'echarts'"
        :option="segment.option"
        class="markdown-embedded-chart"
      />
      <div
        v-else
        class="markdown-text-segment markdown-preview"
        v-html="segment.html"
      ></div>
    </template>
  </div>
</template>

<style scoped>
.markdown-block-root {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* EchartsBlock 自己会按 option 内容算目标 height 并把 wrap 撑到那个高度,这里只管
   装饰(边框/圆角/padding),不强加高度 —— 老版本硬钉 320/360 导致内容溢出被裁。
   box-sizing 把 padding 算进宽度,不然会溢出父气泡。 */
.markdown-embedded-chart {
  box-sizing: border-box;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  padding: 10px;
  display: flex;
}
.markdown-embedded-chart :deep(.echarts-block-wrap) {
  flex: 1;
}

.md-echarts-error {
  padding: 10px 12px;
  background: #fff4f4;
  border: 1px solid #f2caca;
  border-radius: 6px;
  color: #8b2222;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
