// Display the original icon in a rounded rectangle without changing its pixels.
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
const root = new URL('../', import.meta.url);
const icon = fs.readFileSync(new URL('icon.png', root)).toString('base64');
const output = new URL('docs/icon-rounded.svg', root);
fs.writeFileSync(output, `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1024" height="1024" viewBox="0 0 1024 1024" role="img" aria-label="LOOI app icon">
  <defs><clipPath id="rounded"><rect width="1024" height="1024" rx="216"/></clipPath></defs>
  <image width="1024" height="1024" clip-path="url(#rounded)" xlink:href="data:image/png;base64,${icon}"/>
</svg>\n`);
console.log(`Wrote ${fileURLToPath(output)}`);
