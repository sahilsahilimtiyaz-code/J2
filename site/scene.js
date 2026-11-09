import * as THREE from 'https://cdn.jsdelivr.net/npm/three@0.187.0/build/three.module.js';

/* Background: a slowly turning neural Earth — a latitude/longitude globe, glowing
   nodes and arcing connections — with a sparse star field behind it. All of this
   is decoration, so any failure falls back to the CSS gradient. */
const canvas = document.getElementById('bg');
const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/* One soft radial sprite shared by the nodes, stars and the atmosphere glow. */
function softSprite() {
  const size = 96;
  const el = document.createElement('canvas');
  el.width = el.height = size;
  const ctx = el.getContext('2d');
  const gradient = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  gradient.addColorStop(0, 'rgba(255,255,255,1)');
  gradient.addColorStop(0.28, 'rgba(190,235,255,0.62)');
  gradient.addColorStop(0.62, 'rgba(90,175,255,0.18)');
  gradient.addColorStop(1, 'rgba(60,140,255,0)');
  ctx.fillStyle = gradient;
  ctx.beginPath();
  ctx.arc(size / 2, size / 2, size / 2, 0, Math.PI * 2);
  ctx.fill();
  const texture = new THREE.CanvasTexture(el);
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

function start() {
  if (!canvas) return;

  const renderer = new THREE.WebGLRenderer({
    canvas,
    antialias: true,
    alpha: true,
    powerPreference: 'high-performance'
  });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setClearAlpha(0);

  const scene = new THREE.Scene();
  scene.fog = new THREE.FogExp2(0x04070d, 0.035);

  const camera = new THREE.PerspectiveCamera(44, 1, 0.1, 200);
  camera.position.set(0, 0, 7.1);

  /* Tilted frame, so the globe spins on an axis rather than perfectly upright. */
  const tilt = new THREE.Group();
  tilt.rotation.z = 0.2;
  scene.add(tilt);

  const earth = new THREE.Group();
  earth.position.set(0.85, 0.02, 0);
  tilt.add(earth);

  const RADIUS = 2.05;
  const sprite = softSprite();

  /* Atmosphere: a wide additive sprite behind the globe reads as a soft halo. */
  const halo = new THREE.Sprite(new THREE.SpriteMaterial({
    map: sprite,
    color: 0x2f86d8,
    transparent: true,
    opacity: 0.5,
    blending: THREE.AdditiveBlending,
    depthWrite: false
  }));
  halo.scale.setScalar(7.6);
  earth.add(halo);

  /* Opaque core hides the grid and nodes on the far side of the globe. */
  earth.add(new THREE.Mesh(
    new THREE.SphereGeometry(RADIUS * 0.992, 64, 64),
    new THREE.MeshBasicMaterial({ color: 0x050f1c, transparent: true, opacity: 0.95 })
  ));

  /* Graticule: 12 meridians and 5 parallels, drawn as one line buffer. */
  const grid = [];
  const steps = 84;
  for (let m = 0; m < 12; m += 1) {
    const phi = (m / 12) * Math.PI * 2;
    for (let i = 0; i < steps; i += 1) {
      const t0 = (i / steps) * Math.PI;
      const t1 = ((i + 1) / steps) * Math.PI;
      grid.push(
        RADIUS * Math.sin(t0) * Math.cos(phi), RADIUS * Math.cos(t0), RADIUS * Math.sin(t0) * Math.sin(phi),
        RADIUS * Math.sin(t1) * Math.cos(phi), RADIUS * Math.cos(t1), RADIUS * Math.sin(t1) * Math.sin(phi)
      );
    }
  }
  for (let p = 1; p < 6; p += 1) {
    const theta = (p / 6) * Math.PI;
    const ring = RADIUS * Math.sin(theta);
    const y = RADIUS * Math.cos(theta);
    for (let i = 0; i < steps; i += 1) {
      const a0 = (i / steps) * Math.PI * 2;
      const a1 = ((i + 1) / steps) * Math.PI * 2;
      grid.push(
        ring * Math.cos(a0), y, ring * Math.sin(a0),
        ring * Math.cos(a1), y, ring * Math.sin(a1)
      );
    }
  }
  const gridGeometry = new THREE.BufferGeometry();
  gridGeometry.setAttribute('position', new THREE.Float32BufferAttribute(grid, 3));
  earth.add(new THREE.LineSegments(gridGeometry, new THREE.LineBasicMaterial({
    color: 0x2f8fe0,
    transparent: true,
    opacity: 0.17,
    blending: THREE.AdditiveBlending,
    depthWrite: false
  })));

  /* Nodes spread evenly with a Fibonacci sphere. */
  const nodeCount = 132;
  const nodes = [];
  const golden = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < nodeCount; i += 1) {
    const y = 1 - (i / (nodeCount - 1)) * 2;
    const radius = Math.sqrt(Math.max(0, 1 - y * y));
    const theta = golden * i;
    nodes.push(new THREE.Vector3(
      Math.cos(theta) * radius * RADIUS,
      y * RADIUS,
      Math.sin(theta) * radius * RADIUS
    ));
  }
  earth.add(new THREE.Points(
    new THREE.BufferGeometry().setFromPoints(nodes),
    new THREE.PointsMaterial({
      size: 0.095,
      map: sprite,
      color: 0xcdefff,
      transparent: true,
      opacity: 0.95,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
      sizeAttenuation: true
    })
  ));

  /* Neural connections: short arcs lifted off the surface between nearby nodes. */
  const arcPositions = [];
  const maxAngle = 0.62;
  for (let i = 0; i < nodes.length; i += 1) {
    for (let j = i + 1; j < nodes.length; j += 1) {
      const a = nodes[i];
      const b = nodes[j];
      const angle = a.angleTo(b);
      if (angle > maxAngle || Math.random() > 0.3) continue;
      const midpoint = a.clone().add(b).multiplyScalar(0.5).normalize()
        .multiplyScalar(RADIUS * (1 + (angle / maxAngle) * 0.2));
      const points = new THREE.QuadraticBezierCurve3(a, midpoint, b).getPoints(16);
      for (let k = 0; k < points.length - 1; k += 1) {
        arcPositions.push(
          points[k].x, points[k].y, points[k].z,
          points[k + 1].x, points[k + 1].y, points[k + 1].z
        );
      }
    }
  }
  const arcMaterial = new THREE.LineBasicMaterial({
    color: 0x6fcdff,
    transparent: true,
    opacity: 0.34,
    blending: THREE.AdditiveBlending,
    depthWrite: false
  });
  const arcGeometry = new THREE.BufferGeometry();
  arcGeometry.setAttribute('position', new THREE.Float32BufferAttribute(arcPositions, 3));
  earth.add(new THREE.LineSegments(arcGeometry, arcMaterial));

  /* A single thin orbit ring keeps the composition from looking like a plain ball. */
  const orbit = new THREE.Mesh(
    new THREE.TorusGeometry(RADIUS * 1.52, 0.0032, 6, 280),
    new THREE.MeshBasicMaterial({
      color: 0x86d8ff,
      transparent: true,
      opacity: 0.34,
      blending: THREE.AdditiveBlending,
      depthWrite: false
    })
  );
  orbit.rotation.set(Math.PI * 0.5 - 0.22, 0.06, 0.1);
  earth.add(orbit);

  /* Distant stars, dimmer than the globe so it stays the focus. */
  const starCount = 900;
  const starPositions = new Float32Array(starCount * 3);
  for (let i = 0; i < starCount; i += 1) {
    const radius = 5.5 + Math.random() * 9;
    const theta = Math.random() * Math.PI * 2;
    const phi = Math.acos(2 * Math.random() - 1);
    starPositions[i * 3] = radius * Math.sin(phi) * Math.cos(theta);
    starPositions[i * 3 + 1] = radius * Math.cos(phi) * 0.85;
    starPositions[i * 3 + 2] = radius * Math.sin(phi) * Math.sin(theta);
  }
  const starGeometry = new THREE.BufferGeometry();
  starGeometry.setAttribute('position', new THREE.BufferAttribute(starPositions, 3));
  const stars = new THREE.Points(starGeometry, new THREE.PointsMaterial({
    size: 0.05,
    map: sprite,
    color: 0xa9d8ff,
    transparent: true,
    opacity: 0.55,
    depthWrite: false,
    blending: THREE.AdditiveBlending,
    sizeAttenuation: true
  }));
  scene.add(stars);

  const pointer = { x: 0, y: 0, targetX: 0, targetY: 0 };
  window.addEventListener('pointermove', (event) => {
    pointer.targetX = (event.clientX / window.innerWidth - 0.5) * 2;
    pointer.targetY = (event.clientY / window.innerHeight - 0.5) * 2;
  }, { passive: true });

  let scrollY = 0;
  window.addEventListener('scroll', () => {
    scrollY = window.scrollY;
  }, { passive: true });

  function resize() {
    const width = window.innerWidth;
    const height = window.innerHeight;
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
    const wide = width > 940;
    earth.position.x = wide ? 0.92 : 0;
    earth.position.y = wide ? 0.02 : 0.42;
    tilt.scale.setScalar(wide ? 1 : width > 620 ? 0.9 : 0.72);
  }
  resize();
  window.addEventListener('resize', resize);

  const clock = new THREE.Clock();

  function draw() {
    const elapsed = clock.getElapsedTime();
    pointer.x += (pointer.targetX - pointer.x) * 0.05;
    pointer.y += (pointer.targetY - pointer.y) * 0.05;

    earth.rotation.y = elapsed * 0.075 + pointer.x * 0.28;
    tilt.rotation.x = -0.06 + pointer.y * 0.1;
    orbit.rotation.z = 0.1 + elapsed * 0.05;

    /* Slow breathing on the connections gives the network a living quality. */
    arcMaterial.opacity = 0.3 + Math.sin(elapsed * 0.9) * 0.08;
    stars.rotation.y = elapsed * 0.008 + pointer.x * 0.03;

    camera.position.y = -scrollY * 0.0007;
    camera.position.z = 7.1 + Math.min(scrollY, 1600) * 0.0006;
    camera.lookAt(0, 0, 0);

    renderer.render(scene, camera);
  }

  if (reduceMotion) {
    draw();
    window.addEventListener('resize', draw);
    return;
  }

  let frame = 0;
  const loop = () => {
    draw();
    frame = requestAnimationFrame(loop);
  };

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      cancelAnimationFrame(frame);
    } else {
      clock.start();
      loop();
    }
  });

  loop();
}

try {
  start();
} catch (error) {
  document.documentElement.classList.add('no-webgl');
}
