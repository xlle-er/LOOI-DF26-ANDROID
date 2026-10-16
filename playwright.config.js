import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./tests',timeout:30000,workers:1,outputDir:'output/playwright',use:{headless:true,launchOptions:{executablePath:process.env.LOOI_CHROME_PATH || undefined}}});
