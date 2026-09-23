/** IIoT Control Room Dark — loaded explicitly by Tailwind 4 in styles.css. */
module.exports = {
  theme: {
    extend: {
      backgroundColor: {
        deep: '#0B0F19',
        card: '#111827',
      },
      borderColor: {
        muted: '#1F2937',
      },
      colors: {
        status: {
          running: '#10B981',
          warning: '#F59E0B',
          critical: '#EF4444',
          offline: '#6B7280',
        },
      },
      backgroundImage: {
        'ai-gradient': 'linear-gradient(135deg, #6366F1 0%, #8B5CF6 50%, #EC4899 100%)',
      },
      keyframes: {
        'critical-glow': {
          '0%, 100%': { boxShadow: '0 0 0 0 rgb(239 68 68 / 0)' },
          '50%': { boxShadow: '0 0 0 5px rgb(239 68 68 / 0.12), 0 0 16px rgb(239 68 68 / 0.2)' },
        },
        'warning-glow': {
          '0%, 100%': { boxShadow: '0 0 0 0 rgb(245 158 11 / 0)' },
          '50%': { boxShadow: '0 0 0 5px rgb(245 158 11 / 0.12), 0 0 16px rgb(245 158 11 / 0.2)' },
        },
      },
      animation: {
        'critical-glow': 'critical-glow 2.4s ease-in-out infinite',
        'warning-glow': 'warning-glow 2.4s ease-in-out infinite',
      },
    },
  },
};
