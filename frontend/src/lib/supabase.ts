import { createClient } from '@supabase/supabase-js'

// Auth only: all data goes through the API and the pos schema is not exposed (README 12.4).
export const supabase = createClient(import.meta.env.VITE_SUPABASE_URL || 'http://localhost', import.meta.env.VITE_SUPABASE_ANON_KEY || 'missing')
