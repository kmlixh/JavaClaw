import { createApp } from "vue";
import App from "./App.vue";
import { router } from "./router";
import "./styles.css";

// 必须等 router.isReady 再 mount —— 不然 App.vue onMounted 触发时初始 navigation 还没完成,
// route.matched 是空数组,route.query 也读不出来。embed 场景下 ?embed=1 检测就直接挂了。
const app = createApp(App).use(router);
router.isReady().then(() => app.mount("#app"));
