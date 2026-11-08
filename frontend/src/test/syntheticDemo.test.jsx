import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import DemoApp from '../demo/DemoApp.jsx'
import { fixtures, initialDemo, snapshot, transition } from '../demo/model.js'
const step=(s,type)=>transition(s,{type})
function fullTrip(){let s=initialDemo();for(const t of ['register','login:mira','create','invite','login:theo','accept','login:lena','accept','login:arun','accept','login:mira','equal','unequal'])s=step(s,t);return s}
describe('finite fictional environment',()=>{
 it('rejects arbitrary values without reflecting or retaining them',()=>{const s=transition(initialDemo(),{type:'register',email:'PRIVATE_MARKER'});expect(s.registered).toBe(false);expect(JSON.stringify(s)).not.toContain('PRIVATE_MARKER')})
 it('denies anonymous administration and unknown personas',()=>{for(const t of ['create','reset','login:administrator'])expect(step(initialDemo(),t).message).toContain('unavailable')})
 it('requires recipient consent and rejects duplicate or unauthorized actions',()=>{let s=step(step(step(step(initialDemo(),'register'),'login:mira'),'create'),'invite');expect(step(s,'accept').accepted).toEqual([]);s=step(step(s,'login:theo'),'accept');expect(s.accepted).toEqual(['theo']);expect(step(s,'accept').accepted).toEqual(['theo']);expect(step(s,'equal').equal).toBe(false)})
 it.each(['revoke','expire'])('blocks acceptance after %s',t=>{let s=step(step(step(step(step(initialDemo(),'register'),'login:mira'),'create'),'invite'),t);expect(step(step(s,'login:theo'),'accept').accepted).toEqual([])})
 it('keeps verified balances and permits the repayment once only for Lena',()=>{const s=fullTrip();expect(snapshot(s).net).toEqual(['800.00','300.00','-600.00','-500.00']);expect(step(s,'settle').settled).toBe(false);const paid=step(step(s,'login:lena'),'settle');expect(snapshot(paid).net[2]).toBe('0.00');expect(paid.message).toContain('₹600.00');expect(step(paid,'settle').settled).toBe(true);expect(fixtures.circle).toHaveLength(4);expect(fixtures.snapshots.circle.transfers).toEqual([])})
 it('reset is deterministic and fixtures immutable',()=>{expect(step(fullTrip(),'reset')).toEqual(initialDemo());expect(Object.isFrozen(fixtures.expenses[0].split)).toBe(true)})
 it('keeps notification read state scoped to persona',()=>{const s=step(fullTrip(),'read');expect(s.read.mira).toContain('unequal');expect(step(s,'login:theo').read.theo).toBeUndefined()})
})
describe('honest offline experience',()=>{
 it('never calls an API or changes production sessions',async()=>{const fetch=vi.fn();vi.stubGlobal('fetch',fetch);window.sessionStorage.setItem('splitsmart.accessToken','DO_NOT_TOUCH');render(<DemoApp/>);const u=userEvent.setup();await u.click(screen.getByRole('button',{name:'Register fictional Mira'}));await u.click(screen.getByRole('button',{name:'Try as Mira'}));await u.click(screen.getByRole('button',{name:'Create fictional group'}));await u.click(screen.getByRole('button',{name:'Log out'}));expect(fetch).not.toHaveBeenCalled();expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBe('DO_NOT_TOUCH');expect(screen.queryByRole('textbox')).not.toBeInTheDocument()})
 it('recovery honestly has no input or delivery',async()=>{render(<DemoApp/>);await userEvent.click(screen.getByRole('button',{name:'Recovery',exact:true}));expect(screen.getByRole('heading',{name:'Password recovery unavailable'})).toBeVisible();expect(screen.queryByRole('textbox')).not.toBeInTheDocument()})
 it('reset returns to onboarding without any remote admin action',async()=>{render(<DemoApp/>);const u=userEvent.setup();await u.click(screen.getByRole('button',{name:'Register fictional Mira'}));await u.click(screen.getByRole('button',{name:'Try as Mira'}));await u.click(screen.getByRole('button',{name:'Reset this demo'}));await u.click(screen.getByRole('button',{name:'Confirm demo reset'}));expect(screen.getByRole('button',{name:'Register fictional Mira'})).toBeEnabled();expect(screen.queryByRole('button',{name:'Reset this demo'})).not.toBeInTheDocument()})
})
