import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  base: "./",
  build: {
    outDir: "../android/app/src/main/assets/panel",
    emptyOutDir: true,
  },
});