-- ============================================================================
--  Development fixtures: room for the seeded agencies to actually be used
-- ============================================================================
--  The two agencies from V900 start on FREE, which allows five houses. That is
--  the right default for a real agency and the wrong one for a fixture: running
--  the Postman collection creates a house each time, so the sixth run answers
--  402 and every request after it works on an empty {{houseId}} - producing a
--  screenful of 404s and 405s whose cause is nowhere in sight.
--
--  So the development agencies get a plan with headroom. The free tier is still
--  demonstrable, and more honestly: sign an agency up at POST /api/signup and it
--  lands on FREE with five houses, exactly as a real one does.
--
--  Loaded only under the dev profile. Production runs db/migration alone.
-- ============================================================================

UPDATE agency
SET plan            = 'PROFESSIONAL',
    plan_changed_at = now(),
    updated_at      = now()
WHERE agency_id IN ('agency-a', 'agency-b');


-- ============================================================================
--  ...and tell property-service about it
-- ============================================================================
--  This is the part that matters. property-service enforces the ceiling from its
--  own replica of the plan, and it only ever learns of a plan from an event. A
--  row updated here and not announced would leave agency-a on PROFESSIONAL in
--  this database and on the unknown-agency default of FREE in the one doing the
--  enforcing - which is worse than not seeding at all, because the two would
--  disagree and only one of them is visible from the API.
--
--  Written to the outbox rather than published directly, so the relay picks it up
--  on the next poll exactly as it would a plan change made through the API. The
--  fixture uses the real path instead of going around it.
-- ============================================================================

--  The payload column holds the *complete envelope*, not the inner event - the relay
--  is a dumb pipe, so what is committed here is byte-for-byte what a consumer reads.
--  Getting that wrong is silent: the message publishes, the consumer finds no
--  eventType it recognises, and the plan never arrives.

INSERT INTO outbox_event (id, aggregate_type, aggregate_id, agency_id, event_type,
                          payload, created_at, published_at)
SELECT gen_random_uuid(),
       'agency',
       a.agency_id,
       a.agency_id,
       'AgencyPlanChanged',
       jsonb_build_object(
           'eventId', gen_random_uuid(),
           'eventType', 'AgencyPlanChanged',
           'agencyId', a.agency_id,
           'occurredAt', to_char(now() AT TIME ZONE 'UTC',
                                 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
           'payload', jsonb_build_object(
               'agencyId', a.agency_id,
               'plan', a.plan,
               -- Kept in step with SubscriptionPlan by hand, which is tolerable for a
               -- fixture and would not be anywhere else. The enum is the authority.
               'maxHouses', 100,
               'occurredAt', to_char(now() AT TIME ZONE 'UTC',
                                     'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
           )
       ),
       now(),
       NULL
FROM agency a
WHERE a.agency_id IN ('agency-a', 'agency-b');
