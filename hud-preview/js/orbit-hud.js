/*
 * The preview is a still frame of the HUD: the design is the HTML and the CSS, so this file only
 * fits the 1920×1080 stage into the window and keeps the two values that would look wrong frozen —
 * the clock and the frame counter.
 */

const stage = document.getElementById("stage");

function fit() {
  const scale = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
  stage.style.transform = `scale(${scale})`;
}

window.addEventListener("resize", fit);
fit();

function clock() {
  const now = new Date();
  const hh = String(now.getHours()).padStart(2, "0");
  const mm = String(now.getMinutes()).padStart(2, "0");
  document.getElementById("clock").textContent = `${hh}:${mm}`;
}
clock();
setInterval(clock, 10_000);

let frames = 0;
let since = performance.now();
function count(now) {
  frames++;
  if (now - since >= 1000) {
    document.getElementById("fps").textContent = Math.round((frames * 1000) / (now - since));
    frames = 0;
    since = now;
  }
  requestAnimationFrame(count);
}
requestAnimationFrame(count);
