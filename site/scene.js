import * as THREE from 'https://cdn.jsdelivr.net/npm/three@0.187.0/build/three.module.js';

/* Background: the night side of Earth — city lights, a cyan atmospheric limb,
   a web of connections between cities, light trails and a star field.

   Earth imagery is NASA's Blue Marble / Black Marble (public domain), served
   from ./assets. Everything here is decoration: any failure falls back to the
   CSS gradient via the .no-webgl class. */

const canvas = document.getElementById('bg');
const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const DEG = Math.PI / 180;

/* Soft radial glow, shared by nodes, pulses, stars and the haze. */
function glowTexture(inner = 'rgba(255,255,255,1)', mid = 'rgba(190,235,255,0.55)') {
  const size = 128;
  const el = document.createElement('canvas');
  el.width = el.height = size;
  const ctx = el.getContext('2d');
  const g = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  g.addColorStop(0, inner);
  g.addColorStop(0.3, mid);
  g.addColorStop(0.68, 'rgba(80,160,255,0.12)');
  g.addColorStop(1, 'rgba(40,110,255,0)');
  ctx.fillStyle = g;
  ctx.beginPath();
  ctx.arc(size / 2, size / 2, size / 2, 0, Math.PI * 2);
  ctx.fill();
  const texture = new THREE.CanvasTexture(el);
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

/* Equirectangular lat/lon to a point on the sphere, matching three.js UVs. */
function latLonToVec3(lat, lon, radius) {
  const phi = (90 - lat) * DEG;
  const theta = (lon + 180) * DEG;
  return new THREE.Vector3(
    -radius * Math.sin(phi) * Math.cos(theta),
    radius * Math.cos(phi),
    radius * Math.sin(phi) * Math.sin(theta)
  );
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
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.2;

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(40, 1, 0.1, 200);
  camera.position.set(0, 0, 6.4);

  const glow = glowTexture();
  const halo = glowTexture('rgba(255,255,255,0.9)', 'rgba(150,215,255,0.4)');

  /* Parallax wrapper so pointer movement nudges the whole composition. */
  const parallax = new THREE.Group();
  scene.add(parallax);

  /* ---------------- Earth ---------------- */

  const RADIUS = 2.35;
  const globe = new THREE.Group();
  globe.position.set(1.35, 0.5, 0);
  parallax.add(globe);

  /* Tilt, then a spinning inner group (textures plus the network rotate). */
  const tilt = new THREE.Group();
  tilt.rotation.z = -0.16;
  tilt.rotation.x = 0.1;
  globe.add(tilt);

  /* Base rotation faces ~20°E, so Europe, Africa and the Middle East sit
     on the visible night side. */
  const BASE_SPIN = -1.92;
  const spin = new THREE.Group();
  spin.rotation.y = BASE_SPIN;
  tilt.add(spin);

  const loader = new THREE.TextureLoader();
  const maxAniso = renderer.capabilities.getMaxAnisotropy();
  const load = (file) => {
    const texture = loader.load(`./assets/${file}`);
    texture.colorSpace = THREE.SRGBColorSpace;
    texture.anisotropy = Math.min(8, maxAniso);
    return texture;
  };
  const dayMap = load('earth-day.jpg');
  const nightMap = load('earth-night.png');

  const earthMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uDay: { value: dayMap },
      uNight: { value: nightMap },
      uSun: { value: new THREE.Vector3(-0.34, 0.88, -0.36).normalize() },
      uRim: { value: new THREE.Color(0x3f9bff) },
      uReveal: { value: 0.0 }
    },
    vertexShader: /* glsl */`
      varying vec2 vUv;
      varying vec3 vNormalW;
      varying vec3 vViewW;

      void main() {
        vUv = uv;
        vec4 world = modelMatrix * vec4(position, 1.0);
        vNormalW = normalize(mat3(modelMatrix) * normal);
        vViewW = cameraPosition - world.xyz;
        gl_Position = projectionMatrix * viewMatrix * world;
      }
    `,
    fragmentShader: /* glsl */`
      uniform sampler2D uDay;
      uniform sampler2D uNight;
      uniform vec3 uSun;
      uniform vec3 uRim;
      uniform float uReveal;

      varying vec2 vUv;
      varying vec3 vNormalW;
      varying vec3 vViewW;

      void main() {
        vec3 n = normalize(vNormalW);
        vec3 v = normalize(vViewW);
        float ndl = dot(n, uSun);

        vec3 day = texture2D(uDay, vUv).rgb;
        float lum = dot(day, vec3(0.299, 0.587, 0.114));

        /* Dark oceans, faintly lit continents. */
        vec3 ocean = vec3(0.004, 0.013, 0.034);
        vec3 land = vec3(0.007, 0.013, 0.024) + day * vec3(0.03, 0.042, 0.062);
        vec3 albedo = mix(ocean, land, smoothstep(0.035, 0.30, lum));

        float dayAmt = smoothstep(-0.2, 0.32, ndl);
        vec3 color = albedo * (0.3 + dayAmt * 0.7);

        /* City lights: warm pinpoints with a soft photographic bloom. */
        vec2 texel = vec2(0.0018, 0.0018);
        float core = smoothstep(0.07, 0.6, texture2D(uNight, vUv).r);
        float spread = (
          texture2D(uNight, vUv + vec2(texel.x, 0.0)).r
          + texture2D(uNight, vUv - vec2(texel.x, 0.0)).r
          + texture2D(uNight, vUv + vec2(0.0, texel.y)).r
          + texture2D(uNight, vUv - vec2(0.0, texel.y)).r
        ) * 0.25;
        spread = smoothstep(0.06, 0.55, spread);
        float lights = core + spread * 0.75;
        float nightAmt = 1.0 - smoothstep(-0.30, 0.18, ndl);
        color += vec3(1.0, 0.89, 0.72) * lights * (0.75 + nightAmt * 4.2);

        /* Light grazing the horizon reads as the atmospheric limb. */
        float fres = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 3.1);
        color += uRim * fres * (0.55 + 1.4 * max(ndl, 0.0));

        /* A thinner, brighter band right at the sunlit edge. */
        float edge = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 6.5);
        color += vec3(0.72, 0.9, 1.0) * edge * (0.5 + 2.6 * max(ndl, 0.0));

        gl_FragColor = vec4(color * uReveal, 1.0);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }
    `
  });

  spin.add(new THREE.Mesh(new THREE.SphereGeometry(RADIUS, 128, 96), earthMaterial));

  /* Two additive shells: a tight bright limb and a wide soft haze. */
  function atmosphere(radius, color, intensity, power, sunReach = 1.9) {
    return new THREE.Mesh(
      new THREE.SphereGeometry(radius, 96, 64),
      new THREE.ShaderMaterial({
        uniforms: {
          uColor: { value: new THREE.Color(color) },
          uIntensity: { value: intensity },
          uPower: { value: power },
          uSun: { value: new THREE.Vector3(-0.34, 0.88, -0.36).normalize() },
          uSunReach: { value: sunReach },
          uReveal: { value: 0.0 }
        },
        vertexShader: /* glsl */`
          varying vec3 vNormalW;
          varying vec3 vViewW;

          void main() {
            vec4 world = modelMatrix * vec4(position, 1.0);
            vNormalW = normalize(mat3(modelMatrix) * normal);
            vViewW = cameraPosition - world.xyz;
            gl_Position = projectionMatrix * viewMatrix * world;
          }
        `,
        fragmentShader: /* glsl */`
          uniform vec3 uColor;
          uniform float uIntensity;
          uniform float uPower;
          uniform vec3 uSun;
          uniform float uSunReach;
          uniform float uReveal;

          varying vec3 vNormalW;
          varying vec3 vViewW;

          void main() {
            vec3 n = normalize(vNormalW);
            vec3 v = normalize(vViewW);
            float rim = pow(1.0 - abs(dot(n, v)), uPower);
            float lit = 0.26 + uSunReach * max(dot(n, uSun), 0.0);
            float a = clamp(rim * uIntensity * lit * uReveal, 0.0, 1.0);
            gl_FragColor = vec4(uColor * a, a);
          }
        `,
        transparent: true,
        blending: THREE.AdditiveBlending,
        depthWrite: false,
        side: THREE.BackSide
      })
    );
  }

  const innerAtmo = atmosphere(RADIUS * 1.01, 0xdff4ff, 2.2, 7.0, 2.8);
  const outerAtmo = atmosphere(RADIUS * 1.07, 0x3d8fe8, 0.3, 3.2, 1.7);
  spin.add(innerAtmo, outerAtmo);

  /* Wide bloom sitting behind the globe. */
  const bloom = new THREE.Sprite(new THREE.SpriteMaterial({
    map: halo,
    color: 0x2f7fd8,
    transparent: true,
    opacity: 0.2,
    blending: THREE.AdditiveBlending,
    depthWrite: false
  }));
  bloom.scale.setScalar(RADIUS * 2.9);
  globe.add(bloom);

  /* ---------------- Network ---------------- */

  const CITIES = [
    [51.5, -0.1], [48.9, 2.3], [52.5, 13.4], [40.4, -3.7], [55.8, 37.6], [41.0, 29.0],
    [30.0, 31.2], [6.5, 3.4], [-26.2, 28.0], [-1.3, 36.8], [25.2, 55.3], [24.7, 46.7],
    [19.0, 72.9], [28.6, 77.2], [1.35, 103.8], [22.3, 114.2], [35.7, 139.7], [37.6, 127.0],
    [-6.2, 106.8], [-33.9, 151.2], [40.7, -74.0], [34.0, -118.0], [-23.5, -46.6], [19.4, -99.1],
    [43.7, -79.4], [64.1, -21.9]
  ].map(([lat, lon]) => latLonToVec3(lat, lon, RADIUS * 1.002));

  const nodeGeometry = new THREE.BufferGeometry().setFromPoints(CITIES);
  const nodePoints = new THREE.Points(nodeGeometry, new THREE.PointsMaterial({
    size: 0.105,
    map: glow,
    color: 0xe6f8ff,
    transparent: true,
    opacity: 1.0,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    sizeAttenuation: true
  }));
  const nodeHalo = new THREE.Points(nodeGeometry, new THREE.PointsMaterial({
    size: 0.3,
    map: glow,
    color: 0x6ccbff,
    transparent: true,
    opacity: 0.45,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    sizeAttenuation: true
  }));
  spin.add(nodeHalo, nodePoints);

  /* Arcs between neighbouring cities, lifted slightly off the surface. */
  const arcs = [];
  const used = new Set();
  const pairs = [];
  for (let i = 0; i < CITIES.length; i += 1) {
    for (let j = i + 1; j < CITIES.length; j += 1) {
      const angle = CITIES[i].angleTo(CITIES[j]);
      if (angle > 1.15 || angle < 0.08) continue;
      pairs.push({ i, j, angle, score: Math.random() * (1.2 - angle) });
    }
  }
  pairs.sort((a, b) => b.score - a.score);

  const arcPositions = [];
  const arcGlowPositions = [];
  pairs.slice(0, 42).forEach(({ i, j, angle }) => {
    if (used.has(i) && used.has(j) && Math.random() > 0.4) return;
    used.add(i);
    used.add(j);
    const a = CITIES[i];
    const b = CITIES[j];
    const mid = a.clone().add(b).multiplyScalar(0.5).normalize()
      .multiplyScalar(RADIUS * (1 + angle * 0.14));
    const curve = new THREE.QuadraticBezierCurve3(a, mid, b);
    const points = curve.getPoints(22);
    for (let k = 0; k < points.length - 1; k += 1) {
      arcPositions.push(
        points[k].x, points[k].y, points[k].z,
        points[k + 1].x, points[k + 1].y, points[k + 1].z
      );
    }
    for (let k = 0; k <= 24; k += 1) {
      const glowPoint = curve.getPoint(k / 24);
      arcGlowPositions.push(glowPoint.x, glowPoint.y, glowPoint.z);
    }
    arcs.push({ curve, offset: Math.random(), speed: 0.07 + Math.random() * 0.06 });
  });

  const arcGeometry = new THREE.BufferGeometry();
  arcGeometry.setAttribute('position', new THREE.Float32BufferAttribute(arcPositions, 3));
  const arcMaterial = new THREE.LineBasicMaterial({
    color: 0xd6f2ff,
    transparent: true,
    opacity: 0.95,
    blending: THREE.AdditiveBlending,
    depthWrite: false
  });
  spin.add(new THREE.LineSegments(arcGeometry, arcMaterial));

  /* A dotted glow pass over the arcs gives them weight without thick lines. */
  const arcGlowGeometry = new THREE.BufferGeometry();
  arcGlowGeometry.setAttribute(
    'position',
    new THREE.Float32BufferAttribute(arcGlowPositions, 3)
  );
  const arcGlowMaterial = new THREE.PointsMaterial({
    size: 0.068,
    map: glow,
    color: 0xb4eaff,
    transparent: true,
    opacity: 0.5,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    sizeAttenuation: true
  });
  spin.add(new THREE.Points(arcGlowGeometry, arcGlowMaterial));

  /* Pulses travelling along the arcs, updated as one points buffer. */
  const pulsePositions = new Float32Array(arcs.length * 3);
  const pulseGeometry = new THREE.BufferGeometry();
  pulseGeometry.setAttribute('position', new THREE.BufferAttribute(pulsePositions, 3));
  const pulseMaterial = new THREE.PointsMaterial({
    size: 0.15,
    map: glow,
    color: 0xffffff,
    transparent: true,
    opacity: 1.0,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    sizeAttenuation: true
  });
  spin.add(new THREE.Points(pulseGeometry, pulseMaterial));

  /* ---------------- Light trails ---------------- */

  const trailPositions = [];
  const trailColors = [];
  const tint = new THREE.Color(0xbfe8ff);
  for (let i = 0; i < 62; i += 1) {
    const lat = (Math.random() - 0.5) * 150;
    const lon = -60 + Math.random() * 240;
    const base = latLonToVec3(lat, lon, RADIUS * 1.004);
    const outward = base.clone().normalize();
    const tangent = new THREE.Vector3(
      Math.random() - 0.5,
      Math.random() - 0.5,
      Math.random() - 0.5
    ).cross(outward).normalize().multiplyScalar((Math.random() - 0.5) * 1.5);
    const end = outward.clone().multiplyScalar(RADIUS * (1.7 + Math.random() * 2.0)).add(tangent);
    trailPositions.push(base.x, base.y, base.z, end.x, end.y, end.z);
    const strength = 0.75 + Math.random() * 0.55;
    trailColors.push(
      tint.r * strength, tint.g * strength, tint.b * strength,
      0, 0, 0
    );
  }
  const trailGeometry = new THREE.BufferGeometry();
  trailGeometry.setAttribute('position', new THREE.Float32BufferAttribute(trailPositions, 3));
  trailGeometry.setAttribute('color', new THREE.Float32BufferAttribute(trailColors, 3));
  const trailMaterial = new THREE.LineBasicMaterial({
    vertexColors: true,
    transparent: true,
    opacity: 0.85,
    blending: THREE.AdditiveBlending,
    depthWrite: false
  });
  const trails = new THREE.LineSegments(trailGeometry, trailMaterial);
  globe.add(trails);

  /* ---------------- Stars ---------------- */

  function starField(count, radiusMin, radiusMax, size, opacity, color) {
    const positions = new Float32Array(count * 3);
    for (let i = 0; i < count; i += 1) {
      const radius = radiusMin + Math.random() * (radiusMax - radiusMin);
      const theta = Math.random() * Math.PI * 2;
      const phi = Math.acos(2 * Math.random() - 1);
      positions[i * 3] = radius * Math.sin(phi) * Math.cos(theta);
      positions[i * 3 + 1] = radius * Math.cos(phi) * 0.9;
      positions[i * 3 + 2] = radius * Math.sin(phi) * Math.sin(theta);
    }
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    return new THREE.Points(geometry, new THREE.PointsMaterial({
      size,
      map: glow,
      color,
      transparent: true,
      opacity,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
      sizeAttenuation: true
    }));
  }

  const stars = starField(1500, 9, 26, 0.05, 0.5, 0xbcd8ff);
  const brightStars = starField(110, 9, 24, 0.11, 0.75, 0xeaf6ff);
  scene.add(stars, brightStars);

  /* ---------------- Interaction & layout ---------------- */

  const pointer = { x: 0, y: 0, targetX: 0, targetY: 0 };
  window.addEventListener('pointermove', (event) => {
    pointer.targetX = (event.clientX / window.innerWidth - 0.5) * 2;
    pointer.targetY = (event.clientY / window.innerHeight - 0.5) * 2;
  }, { passive: true });

  let scrollY = 0;
  window.addEventListener('scroll', () => {
    scrollY = window.scrollY;
  }, { passive: true });

  const layout = { small: false };

  function resize() {
    const width = window.innerWidth;
    const height = window.innerHeight;
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();

    const wide = width >= 900;
    const small = width < 620;
    layout.small = small;
    globe.position.x = wide ? 0.85 : 0;
    globe.position.y = wide ? 1.55 : small ? 1.15 : 1.0;
    globe.scale.setScalar(wide ? 1.0 : small ? 0.6 : 0.76);
  }
  resize();
  window.addEventListener('resize', resize);

  const clock = new THREE.Clock();

  function draw() {
    const elapsed = clock.getElapsedTime();
    pointer.x += (pointer.targetX - pointer.x) * 0.045;
    pointer.y += (pointer.targetY - pointer.y) * 0.045;

    spin.rotation.y = BASE_SPIN + elapsed * 0.013 + pointer.x * 0.16;
    tilt.rotation.x = 0.1 + pointer.y * 0.06;
    tilt.rotation.z = -0.16 - pointer.x * 0.02;

    parallax.position.x = pointer.x * 0.12;
    parallax.position.y = pointer.y * -0.08;

    /* Keep the network quiet behind phone-width body text. */
    const weight = layout.small ? 0.42 : 1;
    arcMaterial.opacity = (0.7 + Math.sin(elapsed * 0.85) * 0.15) * weight;
    arcGlowMaterial.opacity = (0.42 + Math.sin(elapsed * 0.85) * 0.12) * weight;

    for (let i = 0; i < arcs.length; i += 1) {
      const { curve, offset, speed } = arcs[i];
      const t = (elapsed * speed + offset) % 1;
      const point = curve.getPoint(t);
      pulsePositions[i * 3] = point.x;
      pulsePositions[i * 3 + 1] = point.y;
      pulsePositions[i * 3 + 2] = point.z;
    }
    pulseGeometry.attributes.position.needsUpdate = true;

    trailMaterial.opacity = (0.8 + Math.sin(elapsed * 0.6 + 1.2) * 0.14) * (layout.small ? 0.4 : 1);
    trails.rotation.y = elapsed * 0.006;

    stars.rotation.y = elapsed * 0.005 + pointer.x * 0.02;
    brightStars.rotation.y = elapsed * 0.003;

    /* Fade the imagery in once decoded. */
    earthMaterial.uniforms.uReveal.value += (1 - earthMaterial.uniforms.uReveal.value) * 0.045;
    innerAtmo.material.uniforms.uReveal.value = earthMaterial.uniforms.uReveal.value;
    outerAtmo.material.uniforms.uReveal.value = earthMaterial.uniforms.uReveal.value;

    camera.position.y = -scrollY * 0.0006;
    camera.position.z = 6.4 + Math.min(scrollY, 1600) * 0.0005;
    camera.lookAt(0, 0, 0);

    renderer.render(scene, camera);
  }

  if (reduceMotion) {
    earthMaterial.uniforms.uReveal.value = 1;
    innerAtmo.material.uniforms.uReveal.value = 1;
    outerAtmo.material.uniforms.uReveal.value = 1;
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
