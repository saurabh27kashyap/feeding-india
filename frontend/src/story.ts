import { post } from './api'
import type { DemoState, Role } from './types'

/**
 * One school-meal day, told as a story. The same steps drive the Story page,
 * the Demo Guide, the timeline, and the "click here" highlights.
 */

export interface Person {
  role: Role
  name: string
  emoji: string
  job: string
  where: string
  app: string
}

export const PEOPLE: Person[] = [
  { role: 'CENTRE', name: 'Sunita', emoji: '👩‍🏫', job: 'Runs an Anganwadi (a village pre-school) with 33 children', where: 'Benipur village, Varanasi', app: 'Partner App' },
  { role: 'KITCHEN', name: 'Ramesh', emoji: '👨‍🍳', job: 'Runs a central kitchen that cooks for 100 centres', where: 'Varanasi city', app: 'Partner App' },
  { role: 'OPS', name: 'Saurabh', emoji: '🧑‍💻', job: 'Makes sure all 2,000 centres get fed, every day', where: 'Gurugram office', app: 'Ops Console' },
]

export const personOf = (role: Role) => PEOPLE.find((p) => p.role === role)!

export const PROBLEMS = [
  { emoji: '🗑️', title: 'Food is wasted', text: 'Kitchens cook for every enrolled child, even the ones who do not come that day.' },
  { emoji: '🚫', title: 'A centre gets missed', text: 'A van never leaves, or nobody reports. Children go hungry and nobody notices.' },
  { emoji: '💸', title: 'Wrong bills', text: 'A kitchen bills for more food than the centres actually received.' },
]

export interface Extra {
  label: string
  says: string
  run: (s: DemoState) => Promise<unknown>
}

export interface Step {
  key: string
  when: { date: 'anchor' | 'service'; hhmm: string }
  who: Role
  scene: string
  title: string
  story: string
  problem: string
  click: string
  target?: string
  why: string
  extras?: Extra[]
}

export const STEPS: Step[] = [
  {
    key: 'plan',
    when: { date: 'anchor', hhmm: '19:30' },
    who: 'CENTRE',
    scene: '🌙 👩‍🏫 📱',
    title: 'Sunita plans tomorrow',
    story: 'Monday evening. The app already suggests how many breakfasts Sunita needs tomorrow, from how many children really came on the last 4 Tuesdays.',
    problem: 'Food waste',
    click: 'In the Breakfast card, press + to make it 30, then tap "Save new count".',
    target: 'save-count',
    why: 'Suggestion = average attendance on the last 4 same weekdays + 5% safety buffer. Simple enough for Sunita to check in her head, and a little extra so no child goes hungry.',
  },
  {
    key: 'lock',
    when: { date: 'anchor', hhmm: '20:01' },
    who: 'KITCHEN',
    scene: '🔒 👨‍🍳 📋',
    title: 'At 8 PM, orders lock',
    story: 'At 8:00 PM every centre’s count locks. Ramesh’s kitchen now sees the final number for each route and can plan the early-morning cooking.',
    problem: 'Food waste',
    click: 'See "Final" at the top, the meals per van run, and "What changed": Sunita’s 29 → 30 is there, with her name and time.',
    target: 'final-counts',
    why: 'Every order stores its own cutoff time. If Sunita edits at 7:59:59 PM while the lock runs, only one can win: never a change after the kitchen has the list.',
  },
  {
    key: 'dispatch',
    when: { date: 'service', hhmm: '06:15' },
    who: 'KITCHEN',
    scene: '🌅 🍱 🚚',
    title: 'Morning: the van leaves',
    story: 'Tuesday, 6:15 AM. Breakfast is cooked. Vans must leave by 6:30 AM so food reaches children hot. One van carries Route 001’s food to 10 centres, including Sunita’s.',
    problem: 'Missed centres',
    click: 'Open the "Vans" tab. Route 001’s van sheet lists every stop, worker and phone. Tap "Mark dispatched".',
    target: 'kitchen-tab-vans',
    why: 'Dispatch is per route, like a real van. Every order stores its own "leave by" time, so "late" is a simple query, not a timer. Tapping twice changes nothing.',
  },
  {
    key: 'receive',
    when: { date: 'service', hhmm: '08:30' },
    who: 'CENTRE',
    scene: '🚚 🍽️ 👧👦',
    title: 'Food reaches the children',
    story: 'The van reaches Benipur. Sunita counts 30 meals and taps "Food received". Children scan their faces on a tablet (FRAT) to mark attendance.',
    problem: 'Missed centres',
    click: 'Tap "Food received" (tap it twice: still one receipt). Then run the other centres and the FRAT scan from this guide. Then send feedback with a note.',
    target: 'receive',
    why: 'Receipt is a value, not a +1, so a double tap on a bad network is harmless. Then meals are matched with children present: more children than meals means a hungry child.',
    extras: [{
      label: 'Run the other 1,999 centres’ breakfast',
      says: 'The rest of India had its morning: most vans left on time, a few late, two never left. Most centres confirmed; a few forgot. Saurabh will see all of it.',
      run: (s) => post('/api/demo/simulate/morning', { date: s.serviceDay, mealSlot: 'BREAKFAST' }),
    }, {
      label: 'FRAT scan: 31 children came',
      says: 'The FRAT tablet counted 31 children at breakfast. Only 30 meals came, so one child is short. Saurabh will see this.',
      run: (s) => post('/api/demo/frat', { date: s.serviceDay, mealSlot: 'BREAKFAST', present: 31 }),
    }],
  },
  {
    key: 'ops',
    when: { date: 'service', hhmm: '10:00' },
    who: 'OPS',
    scene: '🏢 🧑‍💻 🚨',
    title: 'Saurabh checks every centre',
    story: '10 AM in the Gurugram office. Breakfast should be confirmed everywhere by 9:30. Saurabh opens the dashboard to see what went wrong, across 2,000 centres at once.',
    problem: 'Missed centres',
    click: 'Look at "Unconfirmed deliveries", "Shortages" and "Late vans". Click Anganwadi Benipur-01 in Shortages to see its full history.',
    target: 'unconfirmed',
    why: '"Not dispatched" means call the kitchen. "Not confirmed" means call the centre. A shortage means more children came than meals arrived.',
  },
  {
    key: 'kitchen-money',
    when: { date: 'service', hhmm: '14:15' },
    who: 'KITCHEN',
    scene: '👨‍🍳 ⭐ ₹',
    title: 'Ramesh checks reviews and money',
    story: 'Lunch is served too. Ramesh reads what centres said about his food, sees how much he will be paid, and sends his bill.',
    problem: 'Wrong bills',
    click: 'Run everyone’s lunch from this guide. Open "Reviews", then "Money & bill": see who to call, and tap "Send bill".',
    target: 'kitchen-tab-money',
    why: 'The money preview uses the same billing rule as Ops approval (pay what centres confirmed), so Ramesh and Saurabh always see the same number.',
    extras: [{
      label: 'Run everyone’s lunch',
      says: 'Lunch happened at all centres, Ramesh’s vans included. Sunita has not confirmed her lunch yet, so Ramesh sees her in “Centres to call”.',
      run: (s) => post('/api/demo/simulate/morning', { date: s.serviceDay, mealSlot: 'LUNCH' }),
    }],
  },
  {
    key: 'bills',
    when: { date: 'service', hhmm: '14:30' },
    who: 'OPS',
    scene: '🧾 ⚖️ ₹',
    title: 'Afternoon: kitchens send bills',
    story: 'All kitchens send their bills for the day. One kitchen bills for more food than the centres received.',
    problem: 'Wrong bills',
    click: 'Run the other kitchens’ bills. Open "Invoices" → Central Kitchen Gurugram-1: see over-billed lines, try "Approve" (blocked: unconfirmed), confirm those centres on their behalf, approve, then "Mark as paid" with a bank reference.',
    target: 'nav-invoices',
    why: 'We pay min(billed, received, ordered). Approval waits until every delivery is confirmed (Ops can confirm after calling a silent centre). Approval freezes the numbers; payment records the bank UTR, and one UTR can pay only one bill.',
    extras: [
      {
        label: 'Other kitchens send their bills',
        says: '19 kitchens sent bills. Central Kitchen Gurugram-1 billed extra meals on some lines.',
        run: (s) => post('/api/demo/simulate/invoices', { date: s.serviceDay }),
      },
    ],
  },
  {
    key: 'scorecard',
    when: { date: 'service', hhmm: '14:45' },
    who: 'OPS',
    scene: '📝 🤖 ⭐',
    title: 'Which kitchens need help?',
    story: 'Centres write notes like “dal was watery” or “food came late”. AI turns thousands of notes into simple labels, so Saurabh sees which kitchen keeps failing.',
    problem: 'Quality',
    click: 'Open "Kitchen scorecard". Kitchens are sorted worst first.',
    target: 'nav-scorecard',
    why: 'AI only labels text (late, quality, hygiene…). It never touches meal counts or money; those follow fixed rules.',
  },
]
