/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/**/*.{js,jsx,ts,tsx}",
    "./public/index.html"
  ],
  theme: {
    extend: {
      colors: {
        // Retro gaming color palette
        'retro-black': '#0f0f0f',
        'retro-dark': '#222222',
        'retro-green': '#33ff33',
        'retro-blue': '#3333ff',
        'retro-red': '#ff3333',
        'retro-yellow': '#ffff33',
        'retro-purple': '#9933ff',
        'retro-cyan': '#33ffff',
        'retro-orange': '#ff9933',
      },
      fontFamily: {
        'press-start': ['"Press Start 2P"', 'cursive'],
        'vt323': ['"VT323"', 'monospace'],
        'pixel': ['"Pixelify Sans"', 'sans-serif'],
      },
      boxShadow: {
        'retro': '0 0 0 2px #33ff33, 0 0 0 4px #0f0f0f',
        'retro-glow': '0 0 5px #33ff33, 0 0 10px #33ff33',
      },
      borderWidth: {
        '3': '3px',
      },
      animation: {
        'pulse-slow': 'pulse 3s cubic-bezier(0.4, 0, 0.6, 1) infinite',
        'blink': 'blink 1s steps(1) infinite',
      },
      keyframes: {
        blink: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0' },
        }
      },
    },
  },
  plugins: [],
}
