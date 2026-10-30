const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const html = fs.readFileSync(process.argv[2], "utf8");
const scripts = [
  ...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi),
];
const inline = scripts.filter(
  ([, attributes]) => !/\bsrc\s*=/.test(attributes)
);
assert.equal(
  inline.length,
  2,
  "Only the storage bootstrap and renderer may be executable inline scripts"
);
const assets = scripts.flatMap(([, attributes]) =>
  [...attributes.matchAll(/\bsrc="([^"]+)"/g)].map((match) => match[1])
);
assert(assets.length > 0);
assert(
  assets.every((url) => url.startsWith("/local/hiprint/")),
  "Only the configured print runtime is loaded"
);
assert.equal(
  new Set(assets).size,
  assets.length,
  "Each runtime asset is loaded once"
);

const tick = () => new Promise((resolve) => setImmediate(resolve));
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
};
function run(options = {}) {
  const handlers = {},
    messages = [];
  let data,
    layout,
    printed = 0,
    inserted = false;
  const parent = { postMessage: (value) => messages.push(value) };
  const context = {
    window: {
      parent,
      addEventListener: (name, handler) => {
        handlers[name] = handler;
      },
      print: () => {
        if (options.printError) throw new Error("browser print refused");
        printed++;
      },
    },
    document: {
      images: options.images || [],
      fonts: { ready: options.fonts || Promise.resolve() },
    },
    hiprint: {
      init() {},
      PrintTemplate: class {
        constructor(value) {
          layout = value.template;
        }
        getHtml(value) {
          if (options.renderError) throw new Error("invalid layout");
          data = value;
          return options.emptyLayout ? [] : ["rendered"];
        }
      },
    },
    $: () => ({
      empty() {
        return this;
      },
      html() {
        inserted = true;
        return this;
      },
    }),
    requestAnimationFrame: (callback) => callback(),
  };
  Object.defineProperty(context.window, "localStorage", {
    configurable: true,
    get() {
      throw new Error("SecurityError: opaque origin");
    },
  });
  for (const [, attributes, code] of scripts) {
    if (/\bsrc\s*=/.test(attributes)) {
      if (attributes.includes("/vue-plugin-hiprint.js")) {
        // The deployed vendor reads window.localStorage during module initialization, before its API is usable.
        const storage = context.window.localStorage;
        storage.setItem("hiprint-preview", "memory-only");
        assert.equal(storage.getItem("hiprint-preview"), "memory-only");
        context.window["vue-plugin-hiprint"] = {
          disAutoConnect() {},
          defaultElementTypeProvider: () => ({}),
        };
      }
    } else {
      vm.runInNewContext(code, context, { timeout: 1000 });
    }
  }
  return {
    messages,
    context,
    parent,
    data: () => data,
    layout: () => layout,
    inserted: () => inserted,
    printed: () => printed,
    send: (source, documentId) =>
      handlers.message({
        source,
        data: { type: "MOM_PRINT_REQUEST", documentId },
      }),
  };
}
async function main() {
  const fonts = deferred(),
    image = deferred();
  const result = run({
    fonts: fonts.promise,
    images: [{ decode: () => image.promise }],
  });
  assert.equal(result.layout().panels[0].name, "派工单");
  const batch = process.argv[3] === "batch";
  assert.equal(Array.isArray(result.data()), batch);
  if (batch) assert.equal(result.data().length, 1);
  const data = batch ? result.data()[0] : result.data();
  assert.equal(data.id, "9007199254740993");
  assert.equal(
    data.name,
    "中文\n第二行</script><script>globalThis.injected=true</script>"
  );
  assert.equal(data.quantity, "12.50");
  assert.equal(
    Object.hasOwn(data, "__proto__"),
    true,
    "JSON field names must remain data fields"
  );
  assert.equal(result.context.injected, undefined);
  assert.equal(result.inserted(), true);
  await tick();
  assert.equal(result.messages.length, 0, "Font rendering is still pending");
  result.send(result.parent, "unknown");
  assert.equal(result.printed(), 0);
  fonts.resolve();
  await tick();
  assert.equal(result.messages.length, 0, "Image decoding is still pending");
  image.resolve();
  await tick();
  const ready = result.messages.at(-1);
  assert.equal(ready.type, "MOM_PRINT_READY");
  result.send({}, ready.documentId);
  result.send(result.parent, "old-document");
  assert.equal(
    result.printed(),
    0,
    "Foreign frames and stale documents cannot print"
  );
  result.send(result.parent, ready.documentId);
  assert.equal(result.printed(), 1);

  const broken = run({ renderError: true });
  await tick();
  assert.equal(broken.messages.at(-1).type, "MOM_PRINT_ERROR");
  broken.send(broken.parent, broken.messages.at(-1).documentId);
  assert.equal(broken.printed(), 0);
  assert.equal(broken.inserted(), false);
  const empty = run({ emptyLayout: true });
  await tick();
  assert.equal(empty.messages.at(-1).type, "MOM_PRINT_ERROR");

  const refused = run({ printError: true });
  await tick();
  const originalDocument = refused.messages.at(-1).documentId;
  refused.send(refused.parent, originalDocument);
  assert.equal(refused.messages.at(-1).type, "MOM_PRINT_ERROR");
  refused.send(refused.parent, originalDocument);
  assert.equal(refused.printed(), 0);

  const failedImage = run({
    images: [{ decode: () => Promise.reject(new Error("missing image")) }],
  });
  await tick();
  assert.equal(failedImage.messages.at(-1).type, "MOM_PRINT_ERROR");
  assert.equal(failedImage.printed(), 0);
  console.log(
    "PASS actual Velocity → JS → HiPrint data, local assets, font/image readiness, parent/document handshake and errors"
  );
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
