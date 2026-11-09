import * as THREE from 'https://cdn.jsdelivr.net/npm/three@0.187.0/build/three.module.js';

/* Animated background: a slowly turning wireframe orb, a halo ring and a drifting
   particle field. Everything here is optional decoration, so failures stay silent. */
const canvas = document.getElementById('bg');
const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

function sprite() {
  const size = 64;
  const el = document.createElement('canvas');
  el.width = el.height = size;
  const ctx = el.getContext('2d');
  const gradient = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  gradient.addColorStop(0, 'rgba(255,255,255,1)');
  gradient.addColorStop(0.35, 'rgba(160,220,255,0.55)');
  gradient.addColorStop(1, 'rgba(90,170,255,0)');
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
  scene.fog = new THREE.FogExp2(0x04070d, 0.05);

  const camera = new THREE.PerspectiveCamera(46, 1, 0.1, 120);
  camera.position.set(0, 0, 7.8);

  const orb = new THREE.Group();
  orb.position.set(0.95, 0.05, 0);
  scene.add(orb);

  const core = new THREE.Mesh(
    new THREE.SphereGeometry(2.03, 48, 48),
    new THREE.MeshBasicMaterial({ color: 0x061423, transparent: true, opacity: 0.74 })
  );
  orb.add(core);

  const innerWire = new THREE.LineSegments(
    new THREE.WireframeGeometry(new THREE.IcosahedronGeometry(2.06, 2)),
    new THREE.LineBasicMaterial({
      color: 0x3aa6ff,
      transparent: true,
      opacity: 0.42,
      blending: THREE.AdditiveBlending,
      depthWrite: false
    })
  );
  orb.add(innerWire);

  const outerWire = new THREE.LineSegments(
    new THREE.WireframeGeometry(new THREE.IcosahedronGeometry(2.42, 1)),
    new THREE.LineBasicMaterial({
      color: 0x8fe3ff,
      transparent: true,
      opacity: 0.15,
      blending: THREE.AdditiveBlending,
      depthWrite: false
    })
  );
  orb.add(outerWire);

  const ring = new THREE.Mesh(
    new THREE.TorusGeometry(2.85, 0.0045, 6, 240),
    new THREE.MeshBasicMaterial({
      color: 0x6fd0ff,
      transparent: true,
      opacity: 0.42,
      blending: THREE.AdditiveBlending,
      depthWrite: false
    })
  );
  ring.rotation.set(Math.PI * 0.44, 0.18, 0.28);
  orb.add(ring);

  const count = 1500;
  const positions = new Float32Array(count * 3);
  for (let i = 0; i < count; i += 1) {
    const radius = 3.1 + Math.random() * 6.4;
    const theta = Math.random() * Math.PI * 2;
    const phi = Math.acos(2 * Math.random() - 1);
    positions[i * 3] = radius * Math.sin(phi) * Math.cos(theta);
    positions[i * 3 + 1] = radius * Math.cos(phi) * 0.72;
    positions[i * 3 + 2] = radius * Math.sin(phi) * Math.sin(theta);
  }
  const pointGeometry = new THREE.BufferGeometry();
  pointGeometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
  const points = new THREE.Points(pointGeometry, new THREE.PointsMaterial({
    size: 0.06,
    map: sprite(),
    color: 0xbfe6ff,
    transparent: true,
    opacity: 0.9,
    depthWrite: false,
    blending: THREE.AdditiveBlending,
    sizeAttenuation: true
  }));
  scene.add(points);

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
    orb.position.x = width > 940 ? 0.95 : 0;
    orb.position.y = width > 940 ? 0.05 : 0.35;
    orb.scale.setScalar(width > 940 ? 1 : width > 620 ? 0.86 : 0.68);
  }
  resize();
  window.addEventListener('resize', resize);

  const clock = new THREE.Clock();

  function draw() {
    const elapsed = clock.getElapsedTime();
    pointer.x += (pointer.targetX - pointer.x) * 0.05;
    pointer.y += (pointer.targetY - pointer.y) * 0.05;

    orb.rotation.y = elapsed * 0.055 + pointer.x * 0.26;
    orb.rotation.x = Math.sin(elapsed * 0.16) * 0.08 + pointer.y * 0.12;
    innerWire.rotation.y = -elapsed * 0.05;
    outerWire.rotation.y = elapsed * 0.035;
    ring.rotation.z = 0.28 + elapsed * 0.05;
    points.rotation.y = elapsed * 0.011 + pointer.x * 0.07;
    points.rotation.x = pointer.y * 0.04;

    camera.position.y = -scrollY * 0.0007;
    camera.position.z = 7.8 + Math.min(scrollY, 1600) * 0.0007;
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
