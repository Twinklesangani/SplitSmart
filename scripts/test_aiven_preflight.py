"""Offline guard tests; no API, credentials, SQL connection or service changes."""
import copy
import unittest
import aiven_postgres_preflight as target

class Fake:
    def __init__(self):
        self.types={'pg':{'service_plans':[{'service_plan':'free-1-1gb','regions':{
            'do-sgp':{'price_usd':'0.000'}}}], 'user_config_schema':{'properties':{
            'pg_version':{'enum':['16','17','18']}}}}}
        self.services=[{'service_name':'splitsmart-mysql','service_type':'mysql'}]
    def get_services(self,project):
        assert project==target.PROJECT
        return copy.deepcopy(self.services)
    def get_service_types(self,project):
        assert project==target.PROJECT
        return copy.deepcopy(self.types)

class FreeGuardTest(unittest.TestCase):
    def test_exact_zero_cost_free_plan_is_accepted(self):
        _,plan,versions=target.prerequisites(Fake())
        self.assertEqual(plan['service_plan'],target.PLAN)
        self.assertIn('16',versions)
    def test_nonzero_price_stops_without_paid_fallback(self):
        fake=Fake();fake.types['pg']['service_plans'][0]['regions']['do-sgp']['price_usd']='0.001'
        with self.assertRaises(target.local.Stop):target.prerequisites(fake)
    def test_missing_free_plan_stops(self):
        fake=Fake();fake.types['pg']['service_plans'][0]['service_plan']='paid-plan'
        with self.assertRaises(target.local.Stop):target.prerequisites(fake)
    def test_missing_existing_mysql_stops(self):
        fake=Fake();fake.services=[]
        with self.assertRaises(target.local.Stop):target.prerequisites(fake)
    def test_redacted_values_are_never_accepted_as_credentials(self):
        for value in [None,'','<redacted>','********','[REDACTED]','hidden']:
            self.assertTrue(target.placeholder(value))
        self.assertFalse(target.placeholder('Synthetic-not-a-real-credential-123'))

if __name__=='__main__':unittest.main()
