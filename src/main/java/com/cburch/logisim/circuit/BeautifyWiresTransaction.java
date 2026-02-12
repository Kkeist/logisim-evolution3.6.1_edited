/*
 * This file is part of logisim-evolution.
 *
 * Logisim-evolution is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Logisim-evolution is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with logisim-evolution. If not, see <http://www.gnu.org/licenses/>.
 */

package com.cburch.logisim.circuit;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Location;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wire beautify: remove pin stubs, empty branches, simplify C-shaped detours.
 */
public class BeautifyWiresTransaction extends CircuitTransaction {

  private final Circuit circuit;
  private final Set<Wire> wiresToBeautify;

  private Set<Location> pinLocations;

  public BeautifyWiresTransaction(
      Circuit circuit, Collection<Wire> wiresToBeautify, Collection<Component> componentsToMove) {
    this.circuit = circuit;
    this.wiresToBeautify =
        (wiresToBeautify == null || wiresToBeautify.isEmpty())
            ? new HashSet<>(circuit.getWires())
            : new HashSet<>(wiresToBeautify);
  }

  @Override
  protected Map<Circuit, Integer> getAccessedCircuits() {
    return Collections.singletonMap(circuit, READ_WRITE);
  }

  @Override
  protected void run(CircuitMutator mutator) {
    ReplacementMap repl = new ReplacementMap();
    pinLocations = new HashSet<>();
    for (Component c : circuit.getNonWires()) {
      for (EndData e : c.getEnds()) pinLocations.add(e.getLocation());
    }

    Set<Wire> effectiveSet = new HashSet<>(wiresToBeautify);
    Map<Wire, List<Wire>> originalsByEffective = new HashMap<>();
    for (Wire w : effectiveSet) {
      originalsByEffective.put(w, new ArrayList<>(Collections.singletonList(w)));
    }
    Map<Location, Set<Wire>> locTo = buildLocToWires(effectiveSet);

    // C-shape first (including half-C at pins: pin-b-c-d -> pin-d, pin as 0-length segment)
    simplifyCDetours(effectiveSet, originalsByEffective, repl, locTo);
    boolean changed = true;
    while (changed) {
      int before = effectiveSet.size();
      removePinStubs(effectiveSet, originalsByEffective, repl, locTo);
      changed = effectiveSet.size() < before;
    }
    changed = true;
    while (changed) {
      int before = effectiveSet.size();
      removeEmptyBranches(effectiveSet, originalsByEffective, repl, locTo);
      changed = effectiveSet.size() < before;
    }
    simplifyCDetours(effectiveSet, originalsByEffective, repl, locTo);

    for (Wire eff : effectiveSet) {
      List<Wire> origs = originalsByEffective.get(eff);
      if (origs == null || origs.isEmpty()) continue;
      repl.put(origs.get(0), Collections.singleton(eff));
      for (int i = 1; i < origs.size(); i++) repl.put(origs.get(i), Collections.emptyList());
    }
    if (!repl.isEmpty()) mutator.replace(circuit, repl);
    new WireRepair(circuit).run(mutator);
  }

  private void removePinStubs(Set<Wire> wires, Map<Wire, List<Wire>> originals, ReplacementMap repl,
      Map<Location, Set<Wire>> locTo) {
    if (wires.isEmpty()) return;
    Set<Wire> toRemove = new HashSet<>();
    for (Wire w : wires) {
      Location a = w.getEnd0(), b = w.getEnd1();
      boolean aPin = pinLocations.contains(a);
      boolean bPin = pinLocations.contains(b);
      int da = locTo.getOrDefault(a, Collections.emptySet()).size();
      int db = locTo.getOrDefault(b, Collections.emptySet()).size();
      if ((aPin && !bPin && db == 1) || (bPin && !aPin && da == 1)) toRemove.add(w);
    }
    for (Wire w : toRemove) {
      wires.remove(w);
      locTo.getOrDefault(w.getEnd0(), Collections.emptySet()).remove(w);
      locTo.getOrDefault(w.getEnd1(), Collections.emptySet()).remove(w);
      List<Wire> origs = originals.remove(w);
      if (origs != null) for (Wire o : origs) repl.put(o, Collections.emptyList());
    }
  }

  private void removeEmptyBranches(Set<Wire> wires, Map<Wire, List<Wire>> originals, ReplacementMap repl,
      Map<Location, Set<Wire>> locTo) {
    if (wires.isEmpty()) return;
    Set<Location> canReachPin = new HashSet<>(pinLocations);
    ArrayDeque<Location> queue = new ArrayDeque<>(pinLocations);
    while (!queue.isEmpty()) {
      Location u = queue.poll();
      for (Wire w : locTo.getOrDefault(u, Collections.emptySet())) {
        Location v = w.getOtherEnd(u);
        if (canReachPin.add(v)) {
          queue.add(v);
        }
      }
    }
    
    // Branch points (degree > 1, not pin)
    Set<Location> branchPoints = new HashSet<>();
    for (Map.Entry<Location, Set<Wire>> e : locTo.entrySet()) {
      Location loc = e.getKey();
      if (!pinLocations.contains(loc) && e.getValue().size() > 1) {
        branchPoints.add(loc);
      }
    }
    
    Set<Wire> toRemove = new HashSet<>();
    Set<String> processed = new HashSet<>();
    
    for (Location branchPoint : branchPoints) {
      Set<Wire> branchWires = locTo.getOrDefault(branchPoint, Collections.emptySet());
      for (Wire startWire : branchWires) {
        Location nextLoc = startWire.getOtherEnd(branchPoint);
        String pathKey = branchPoint + "->" + nextLoc;
        if (processed.contains(pathKey)) continue;
        processed.add(pathKey);
        
        Set<Wire> pathWires = new HashSet<>();
        Set<Location> pathLocs = new HashSet<>();
        ArrayDeque<Location> dfsQueue = new ArrayDeque<>();
        dfsQueue.add(nextLoc);
        pathLocs.add(branchPoint);
        pathLocs.add(nextLoc);
        pathWires.add(startWire);
        
        boolean reachedPin = false;
        Set<Location> reachedBranchPoints = new HashSet<>();
        
        while (!dfsQueue.isEmpty()) {
          Location u = dfsQueue.poll();
          
          if (pinLocations.contains(u)) {
            reachedPin = true;
            break;
          }
          
          if (!u.equals(branchPoint) && branchPoints.contains(u)) {
            reachedBranchPoints.add(u);
            if (canReachPin.contains(u)) {
              reachedPin = true;
              break;
            }
            continue;
          }
          
          Set<Wire> neighbors = locTo.getOrDefault(u, Collections.emptySet());
          for (Wire w : neighbors) {
            if (pathWires.contains(w)) continue;
            Location v = w.getOtherEnd(u);
            
            if (v.equals(branchPoint)) continue;
            if (pathLocs.contains(v)) continue;
            
            pathWires.add(w);
            pathLocs.add(v);
            dfsQueue.add(v);
          }
        }
        
        if (!reachedPin) {
          boolean connectedViaBranch = false;
          for (Location bp : reachedBranchPoints) {
            if (canReachPin.contains(bp)) {
              connectedViaBranch = true;
              break;
            }
          }
          
          if (!connectedViaBranch) {
            toRemove.addAll(pathWires);
          }
        }
      }
    }
    
    for (Wire w : wires) {
      if (toRemove.contains(w)) continue;
      Location a = w.getEnd0(), b = w.getEnd1();
      boolean aPin = pinLocations.contains(a);
      boolean bPin = pinLocations.contains(b);
      if (aPin || bPin) continue;
      
      int da = locTo.getOrDefault(a, Collections.emptySet()).size();
      int db = locTo.getOrDefault(b, Collections.emptySet()).size();
      boolean aOk = canReachPin.contains(a);
      boolean bOk = canReachPin.contains(b);
      
      if (da == 1 && db == 1 && !aOk && !bOk) {
        toRemove.add(w);
      }
    }
    
    for (Wire w : toRemove) {
      wires.remove(w);
      locTo.getOrDefault(w.getEnd0(), Collections.emptySet()).remove(w);
      locTo.getOrDefault(w.getEnd1(), Collections.emptySet()).remove(w);
      List<Wire> origs = originals.remove(w);
      if (origs != null) for (Wire o : origs) repl.put(o, Collections.emptyList());
    }
  }

  /** Replace C-shaped path a-b-c-d (a,d aligned) with straight a-d. a or d may be pin (half-C). */
  private void simplifyCDetours(Set<Wire> wires, Map<Wire, List<Wire>> originals, ReplacementMap repl,
      Map<Location, Set<Wire>> locTo) {
    if (wires.isEmpty()) return;
    Map<Location, Set<Location>> nbr = buildNeighborsFromLocTo(locTo);
    Set<String> done = new HashSet<>();
    for (Location b : new HashSet<>(nbr.keySet())) {
      if (pinLocations.contains(b)) continue;
      Set<Location> bNeighbors = nbr.getOrDefault(b, Collections.emptySet());
      if (bNeighbors.size() != 2) continue;
      
      var itB = bNeighbors.iterator();
      Location a = itB.next();
      Location c = itB.next();
      if (c == null) continue;
      
      if (pinLocations.contains(c)) continue;
      Set<Location> cNeighbors = nbr.getOrDefault(c, Collections.emptySet());
      if (cNeighbors.size() != 2) continue;
      
      var itC = cNeighbors.iterator();
      Location d0 = itC.next();
      Location d1 = itC.next();
      Location d = d0.equals(b) ? d1 : d0;
      if (d == null) continue;
      
      if (a.equals(d)) continue;
      if (a.getX() != d.getX() && a.getY() != d.getY()) continue;
      
      Wire w1 = Wire.create(a, b);
      Wire w2 = Wire.create(b, c);
      Wire w3 = Wire.create(c, d);
      if (!(wires.contains(w1) && wires.contains(w2) && wires.contains(w3))) continue;
      
      Wire straight = Wire.create(a, d);
      if (wires.contains(straight)) continue;
      
      boolean overlaps = false;
      for (Wire ow : wires) {
        if (ow.equals(w1) || ow.equals(w2) || ow.equals(w3)) continue;
        if (straight.overlaps(ow, false)) {
          overlaps = true;
          break;
        }
      }
      if (overlaps) continue;
      
      String key = segmentKey(a, d);
      if (done.contains(key)) continue;
      done.add(key);
      
      wires.remove(w1);
      wires.remove(w2);
      wires.remove(w3);
      wires.add(straight);
      
      List<Wire> combined = new ArrayList<>();
      List<Wire> o1 = originals.remove(w1);
      List<Wire> o2 = originals.remove(w2);
      List<Wire> o3 = originals.remove(w3);
      if (o1 != null) combined.addAll(o1);
      if (o2 != null) combined.addAll(o2);
      if (o3 != null) combined.addAll(o3);
      originals.put(straight, combined);
      
      locTo.getOrDefault(a, Collections.emptySet()).remove(w1);
      locTo.getOrDefault(b, Collections.emptySet()).remove(w1);
      locTo.getOrDefault(b, Collections.emptySet()).remove(w2);
      locTo.getOrDefault(c, Collections.emptySet()).remove(w2);
      locTo.getOrDefault(c, Collections.emptySet()).remove(w3);
      locTo.getOrDefault(d, Collections.emptySet()).remove(w3);
      locTo.computeIfAbsent(a, k -> new HashSet<>()).add(straight);
      locTo.computeIfAbsent(d, k -> new HashSet<>()).add(straight);
      
      for (int i = 0; i < combined.size(); i++) {
        repl.put(combined.get(i), i == 0 ? Collections.singleton(straight) : Collections.emptyList());
      }
    }
  }

  private Map<Location, Set<Wire>> buildLocToWires(Set<Wire> wires) {
    Map<Location, Set<Wire>> map = new HashMap<>();
    for (Wire w : wires) {
      map.computeIfAbsent(w.getEnd0(), k -> new HashSet<>()).add(w);
      map.computeIfAbsent(w.getEnd1(), k -> new HashSet<>()).add(w);
    }
    return map;
  }

  private static Map<Location, Set<Location>> buildNeighborsFromLocTo(Map<Location, Set<Wire>> locTo) {
    Map<Location, Set<Location>> map = new HashMap<>();
    for (Map.Entry<Location, Set<Wire>> e : locTo.entrySet()) {
      Location loc = e.getKey();
      for (Wire w : e.getValue()) {
        map.computeIfAbsent(loc, k -> new HashSet<>()).add(w.getOtherEnd(loc));
      }
    }
    return map;
  }

  private static String segmentKey(Location a, Location b) {
    int ax = a.getX(), ay = a.getY(), bx = b.getX(), by = b.getY();
    if (ax < bx || (ax == bx && ay <= by)) return ax + "," + ay + "-" + bx + "," + by;
    return bx + "," + by + "-" + ax + "," + ay;
  }
}
