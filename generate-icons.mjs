import sharp from 'sharp'
import { mkdirSync, existsSync } from 'fs'
import { join } from 'path'

const svgPath = 'src/assets/app-icon.svg'
const androidResDir = 'src-capacitor/android/app/src/main/res'

const sizes = [
  { dir: 'mipmap-mdpi', size: 48 },
  { dir: 'mipmap-hdpi', size: 72 },
  { dir: 'mipmap-xhdpi', size: 96 },
  { dir: 'mipmap-xxhdpi', size: 144 },
  { dir: 'mipmap-xxxhdpi', size: 192 }
]

async function generateIcons() {
  for (const { dir, size } of sizes) {
    const outDir = join(androidResDir, dir)
    if (!existsSync(outDir)) mkdirSync(outDir, { recursive: true })

    // Standard launcher icon
    await sharp(svgPath, { density: Math.round(size * 72 / 48) })
      .resize(size, size)
      .png()
      .toFile(join(outDir, 'ic_launcher.png'))

    // Round launcher icon
    await sharp(svgPath, { density: Math.round(size * 72 / 48) })
      .resize(size, size)
      .png()
      .toFile(join(outDir, 'ic_launcher_round.png'))

    // Adaptive icon foreground — 108dp canvas, content in inner 72dp (66%)
    // The icon content should occupy ~66% of the canvas, centered
    const fgCanvas = Math.round(size * 108 / 48)  // full adaptive canvas
    const iconSize = Math.round(size * 82 / 48)    // ~76% of canvas — larger but within safe area
    const padding = Math.round((fgCanvas - iconSize) / 2)

    const iconBuffer = await sharp(svgPath, { density: Math.round(iconSize * 72 / 48) })
      .resize(iconSize, iconSize)
      .png()
      .toBuffer()

    await sharp({
      create: {
        width: fgCanvas,
        height: fgCanvas,
        channels: 4,
        background: { r: 5, g: 5, b: 8, alpha: 255 } // #050508 background
      }
    })
      .composite([{ input: iconBuffer, left: padding, top: padding }])
      .png()
      .toFile(join(outDir, 'ic_launcher_foreground.png'))

    console.log(`Generated ${dir}: ${size}x${size} (fg: ${fgCanvas}x${fgCanvas})`)
  }

  // Web favicons
  const publicDir = 'public/icons'
  if (!existsSync(publicDir)) mkdirSync(publicDir, { recursive: true })

  for (const s of [16, 32, 96, 128, 192, 512]) {
    await sharp(svgPath, { density: Math.round(s * 72 / 48) })
      .resize(s, s)
      .png()
      .toFile(join(publicDir, `favicon-${s}x${s}.png`))
    console.log(`Generated favicon-${s}x${s}.png`)
  }

  await sharp(svgPath, { density: 72 })
    .resize(32, 32)
    .png()
    .toFile(join('public', 'favicon.ico'))

  console.log('All icons generated!')
}

generateIcons().catch(console.error)
