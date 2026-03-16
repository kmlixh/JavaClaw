<script setup>
import * as echarts from "echarts";
import { onBeforeUnmount, onMounted, ref, watch } from "vue";

const props = defineProps({
  option: {
    type: Object,
    required: true
  }
});

const root = ref(null);
let chart = null;

function render() {
  if (!root.value) return;
  if (!chart) {
    chart = echarts.init(root.value);
  }
  chart.setOption(props.option || {}, true);
}

onMounted(() => {
  render();
  window.addEventListener("resize", render);
});

watch(() => props.option, () => render(), { deep: true });

onBeforeUnmount(() => {
  window.removeEventListener("resize", render);
  if (chart) {
    chart.dispose();
    chart = null;
  }
});
</script>

<template>
  <div ref="root" class="echarts-block"></div>
</template>
